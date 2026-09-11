package net.itzq.mira.modules.ai.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONPath;
import com.fasterxml.jackson.databind.JavaType;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.core.utils.IdGen;
import net.itzq.mira.core.utils.JsonMapper;
import net.itzq.mira.modules.ai.agent.event.EventCenter;
import net.itzq.mira.modules.ai.agent.event.EventHook;
import net.itzq.mira.modules.ai.agent.event.type.*;
import net.itzq.mira.modules.ai.client.config.ApiProviderManage;
import net.itzq.mira.modules.ai.client.handle.ApiRequestParams;
import net.itzq.mira.modules.ai.client.handle.OpenAICompatibleChatService;
import net.itzq.mira.modules.ai.client.handle.SseEventListener;
import net.itzq.mira.modules.ai.client.handle.StreamEventHandler;
import net.itzq.mira.modules.ai.client.openai.chat.entity.ChatMessage;
import net.itzq.mira.modules.ai.client.openai.chat.entity.Content;
import net.itzq.mira.modules.ai.client.openai.chat.enums.ChatMessageType;
import net.itzq.mira.modules.ai.client.openai.tool.Tool;
import net.itzq.mira.modules.ai.client.openai.tool.ToolCall;
import net.itzq.mira.modules.ai.client.tool.FCUtil;
import net.itzq.mira.modules.ai.entity.chat.ReplyId;
import net.itzq.mira.modules.ai.persistence.AbstractHistoryPersist;
import net.itzq.mira.modules.ai.utils.ThreadPoolUtil;
import org.apache.commons.lang3.StringUtils;

import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BasicAgent - 支持工具调用的自动Agent
 *
 * @author tangzq
 */
@Slf4j
public class BasicAgent {

    @Getter
    @Setter
    private int maxDepth = 999; // 最大循环次数

    @Getter
    @Setter
    private AgentContextHolder contextHolder;

    @Getter
    @Setter
    private String name;

    @Getter
    @Setter
    private String agentName;

    @Getter
    @Setter
    private String agentSrcId;
    @Getter
    @Setter
    private String agentId;

    @Getter
    @Setter
    private String historyId;

    @Getter
    private boolean mainAgent = false;

    @Getter
    private boolean streamChat = false;

    @Getter
    @Setter
    private AbstractHistoryPersist historyPersist = null;

    private final Queue<ChatMessage> appendChatMessageHistoryQueue = new ConcurrentLinkedQueue<>();

    public BasicAgent(AgentContextHolder contextHolder) {
        this(contextHolder, IdGen.uuidShort(), false);
    }

    public BasicAgent(AgentContextHolder contextHolder, String name) {
        this(contextHolder, name, false);
    }

    public BasicAgent(AgentContextHolder contextHolder, String name, boolean main) {
        // 本类自身完整装配（不依赖父类字段，保证整体置换后自洽）
        if (contextHolder == null) {
            contextHolder = AgentContextHolder.builder().build();
        }

        mainAgent = false;
        streamChat = false;
        String uuidShort = IdGen.uuidShort();

        this.historyId = contextHolder.getHistoryId();
        if (StringUtils.isBlank(this.historyId)) {
            this.historyId = IdGen.uuid();
            contextHolder.setHistoryId(this.historyId);
        }

        this.contextHolder = contextHolder;
        if (StringUtils.isNotBlank(name)) {
            this.name = name;
        } else {
            this.name = uuidShort;
        }

        this.agentSrcId = uuidShort;
        this.agentId = uuidShort;
        this.agentName = this.name;

        if (contextHolder.getTopAgent() == null) {
            contextHolder.setTopAgent(this);
        }

        setMainAgent(main);
    }

    public synchronized BasicAgent setMainAgent(boolean main) {
        this.mainAgent = main;
        if (main) {
            this.agentId = "main-" + this.agentSrcId;
        } else {
            this.agentId = this.agentSrcId;
        }
        return this;
    }

    /**
     * 阶段判定
     */
    public enum AgentPhase {

        NormalMessaeg, // 归一化后message数组的结尾消息是user或者assistant消息，下一步应该直接往api发送message请求

        ExecuteTools, // 归一化后message数组的结尾是待调用工具的参数，下一步应该调用工具

        ToolsResultReady, // 归一化后message数组的结尾是工具调用结果，下一步应该直接往api发送message请求
    }

    /**
     * 单步执行：判定当前阶段，执行对应动作，返回结果
     *
     * @param resumeFromBreakpoint true: 完整修复归一化（用户取消等终态后恢复）
     *                             false: 基础归一化（正常断点续跑）
     * @param hasError
     */
    public synchronized void dispatch(boolean resumeFromBreakpoint, AtomicBoolean hasError) throws InterruptedException {
        List<ChatMessage> messages = normalizeMessages(resumeFromBreakpoint);
        if (messages.isEmpty()) {
            return;
        }

        ChatMessage last = messages.get(messages.size() - 1);
        String role = last.getRole();

        // ====== 依据归一化后消息数组的结尾判定当前阶段 ======
        AgentPhase phase;
        if (ChatMessageType.ASSISTANT.getRole().equals(role) && last.getToolCalls() != null && !last.getToolCalls()
                .isEmpty()) {
            phase = AgentPhase.ExecuteTools;
        } else if (ChatMessageType.TOOL.getRole().equals(role)) {
            Set<String> resolvedIds = new HashSet<>();
            int i = messages.size() - 1;
            while (i >= 0 && ChatMessageType.TOOL.getRole().equals(messages.get(i).getRole())) {
                if (StringUtils.isNotBlank(messages.get(i).getToolCallId())) {
                    resolvedIds.add(messages.get(i).getToolCallId());
                }
                i--;
            }
            ChatMessage toolCallOwner = null;
            if (i >= 0 && ChatMessageType.ASSISTANT.getRole().equals(messages.get(i).getRole())
                    && messages.get(i).getToolCalls() != null && !messages.get(i).getToolCalls().isEmpty()) {
                toolCallOwner = messages.get(i);
            }

            if (toolCallOwner == null) {
                phase = AgentPhase.ToolsResultReady;
            } else {
                boolean allResolved = true;
                for (ToolCall toolCall : toolCallOwner.getToolCalls()) {
                    // 空白/缺失 id 的 toolCall 无法与 tool 结果消息关联，视为已处理，
                    // 否则会被判定为“未完成”而在每轮 dispatch 中重复执行直至 maxDepth
                    if (toolCall == null || StringUtils.isBlank(toolCall.getId())) {
                        continue;
                    }
                    if (!resolvedIds.contains(toolCall.getId())) {
                        allResolved = false;
                        break;
                    }
                }
                phase = allResolved ? AgentPhase.ToolsResultReady : AgentPhase.ExecuteTools;
            }
        } else {
            phase = AgentPhase.NormalMessaeg;
        }

        // ====== 按阶段执行对应处理方法 ======

        if (phase == AgentPhase.NormalMessaeg) {
            // NormalMessaeg：结尾是 user 或普通 assistant 消息，直接往 API 发送请求
            handleAI(messages, hasError);
        }
        if (phase == AgentPhase.ExecuteTools) {
            handleExecuteTools(messages);
        }
        if (phase == AgentPhase.ToolsResultReady) {
            // ToolsResultReady：结尾是已完成的工具调用结果，直接往 API 发送请求
            handleAI(messages, hasError);
        }

    }

    /**
     * 调用 AI 的统一入口：依据 streamChat 标志选择底层请求机制，两者不混用
     * - true：SSE 流式请求（stream=true，见 handleAIStream）
     * - false：同步请求（stream=false，见 handleAISync）
     */
    private void handleAI(List<ChatMessage> messages, AtomicBoolean hasError) throws InterruptedException {
        if (streamChat) {
            handleAIStream(messages, hasError);
        } else {
            handleAISync(messages, hasError);
        }
    }

    // ==================== 阶段处理方法 ====================

    /**
     * ExecuteTools：结尾是待调用工具的参数，执行剩余未回填结果的工具调用
     */
    private void handleExecuteTools(List<ChatMessage> messages) {
        // 从归一化后消息末尾回溯，找到带 tool_calls 的 assistant 和未解决的 tool_calls
        int i = messages.size() - 1;
        while (i >= 0 && ChatMessageType.TOOL.getRole().equals(messages.get(i).getRole())) {
            i--;
        }

        ChatMessage toolCallOwner = null;
        if (i >= 0 && ChatMessageType.ASSISTANT.getRole().equals(messages.get(i).getRole())
                && messages.get(i).getToolCalls() != null && !messages.get(i).getToolCalls().isEmpty()) {
            toolCallOwner = messages.get(i);
        }

        if (toolCallOwner == null) {
            // 无待执行的工具调用，正常结束
            return;
        }

        // 已有结果的 tool_call_id
        Set<String> resolvedIds = new HashSet<>();
        for (int j = i + 1; j < messages.size(); j++) {
            if (ChatMessageType.TOOL.getRole().equals(messages.get(j).getRole()) && StringUtils.isNotBlank(messages.get(
                    j).getToolCallId())) {
                resolvedIds.add(messages.get(j).getToolCallId());
            }
        }

        // 过滤出尚未执行的工具调用（空白/缺失 id 无法与已有结果关联，跳过以避免重复执行）
        List<ToolCall> unresolved = new ArrayList<>();
        for (ToolCall tc : toolCallOwner.getToolCalls()) {
            if (tc != null && StringUtils.isNotBlank(tc.getId()) && !resolvedIds.contains(tc.getId())) {
                unresolved.add(tc);
            }
        }

        if (unresolved.isEmpty()) {
            // 全部已执行，无剩余工具，正常结束
            return;
        }

        log.info("AutoAgent【{}】ExecuteTools: 执行{}个剩余工具调用", name, unresolved.size());

        executeToolCalls(unresolved);
    }

    private void handleAIStream(List<ChatMessage> messages, AtomicBoolean hasError) throws InterruptedException {
        String msgId = IdGen.uuid();
        int currentDeep = contextHolder.getCurrentDeep();

        String modelAlias = contextHolder.getModelAlias();
        if (StringUtils.isBlank(modelAlias)) {
            modelAlias = ApiProviderManage.getDefaultModel();
        }
        List<String> tools = contextHolder.getTools();
        String prompt = contextHolder.getPrompt();

        long begin = System.currentTimeMillis();

        OpenAICompatibleChatService chatService = ApiProviderManage.getChatService(modelAlias);
        // 归一化副本不含系统提示词，此处统一补充
        List<ChatMessage> apiMessages = buildMessages(messages, prompt, tools);

        ApiRequestParams apiRequestParams = contextHolder.getRequestParams();
        if (tools != null && !tools.isEmpty()) {
            List<Tool> allFunctionTools = FCUtil.getAllFunctionTools(tools);
            apiRequestParams.setTools(allFunctionTools);
        }
        apiRequestParams.setMessages(apiMessages);

        EventCenter eventCenter = contextHolder.getEventCenter();
        EventHook eventHook = contextHolder.getEventHook();

        final CountDownLatch latch = new CountDownLatch(1);

        SseEventListener listener = new SseEventListener() {
            @Override
            public void onEvent(String eventType, String data, String id, StreamEventHandler handler) {

                log.debug("Stream event: {}", handler.getCurrStr());

                ReplyId replyId = new ReplyId(historyId,
                        agentId,
                        contextHolder.getCurrentChatLoopRoundId(),
                        currentDeep,
                        msgId,
                        agentName);
                if (contextHolder.getParentAgent() != null) {
                    replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                }

                GeneralEvent event = new GeneralEvent();
                event.setEventType(eventType);
                event.setData(data);
                event.setId(id);
                event.setHandler(handler);
                event.setReplyId(replyId);
                event.setContext(contextHolder);

                if (eventHook != null) {
                    eventHook.onEvent(event);
                }
                if (eventCenter != null) {
                    eventCenter.fireEvent(event);
                }
            }

            @Override
            public void onComment(String comment, StreamEventHandler handler) {

            }

            @Override
            public void onComplete(StreamEventHandler handler) {
                try {

                    if (handler.needToolCall()) {
                        List<ToolCall> toolCalls = handler.getToolCalls();
                        log.info("AutoAgent【{}】第{}轮收到{}个工具调用", name, currentDeep, toolCalls.size());

                        // 将AI的工具调用响应加入历史
                        ChatMessage responseMessage = ChatMessage.withAssistant(toolCalls);
                        appendHistory(responseMessage);

                        // 执行工具调用（与 BasicAgent 一致：本轮内立即执行，事件携带当前 handler）
                        executeToolCalls(toolCalls);
                    } else {
                        // 普通回复，已经通过 onEvent 逐步发送，这里只需要记录
                        log.info("AutoAgent【{}】第{}轮完成，无工具调用", name, currentDeep);

                        // 将AI的响应加入历史
                        ChatMessage responseMessage = ChatMessage.withAssistant(handler.getAnswerOutput().toString());
                        appendHistory(responseMessage);
                    }

                } catch (Exception e) {
                    log.error("处理流式完成回调失败", e);
                    hasError.set(true);
                } finally {
                    long end = System.currentTimeMillis();
                    double time = (end - begin) / 1000.0;
                    log.info("[AutoAgent-{}] 第{}轮 onComplete 耗时{}s", name, currentDeep, time);
                    latch.countDown();
                }
            }

            @Override
            public void onError(Throwable t, StreamEventHandler handler) {

                log.error("流式处理出错", t);

                hasError.set(true);

                ReplyId replyId = new ReplyId(historyId,
                        agentId,
                        contextHolder.getCurrentChatLoopRoundId(),
                        currentDeep,
                        msgId,
                        agentName);
                if (contextHolder.getParentAgent() != null) {
                    replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                }

                ErrorEvent event = new ErrorEvent();
                event.setThrowable(t);
                event.setReplyId(replyId);
                event.setContext(contextHolder);

                if (eventHook != null) {
                    eventHook.onError(event);
                }
                if (eventCenter != null) {
                    eventCenter.fireError(event);
                }

                latch.countDown();
            }
        };

        try {
            chatService.getResult(apiRequestParams, null, listener);
        } catch (Exception e) {
            log.error("流式请求失败", e);

            hasError.set(true);

            latch.countDown(); // 确保 latch 被释放，避免外层永远阻塞
            return;
        }

        // dispatch 为同步推进：阻塞等待本轮完成（超时语义与 BasicAgent 一致，不置 hasError）。
        // 此处去掉了timeout字段避免歧义，正常应由 httpclient 控制api处超时抛出异常，1小时仅为兜底。
        // InterruptedException 不在此捕获包装，保持原始异常向上传播，
        // 由外层 chatStream 的 catch 统一转 ChatEnd(false)（与 BasicAgent 一致）。
        boolean completed = latch.await(1, TimeUnit.HOURS);
        if (!completed) {
            log.error("AutoAgent【{}】第{}轮流式调用超时，作废本轮", name, currentDeep);
        }
    }

    /**
     * 同步 AI 调用：发送 stream=false 的非流式请求（chat 模式专用，与 BasicAgent.callAI 一致），
     * 不监听 SSE、不触发 GeneralEvent。解析响应后追加 assistant 消息；
     * 带工具调用则本轮内立即执行工具（与 handleAIStream 的 onComplete 行为一致）。
     * 请求失败通过 hasError 上报（与 handleAIStream 的 onError 语义一致），不追加消息。
     */
    private void handleAISync(List<ChatMessage> messages, AtomicBoolean hasError) {
        int currentDeep = contextHolder.getCurrentDeep();

        String modelAlias = contextHolder.getModelAlias();
        if (StringUtils.isBlank(modelAlias)) {
            modelAlias = ApiProviderManage.getDefaultModel();
        }
        List<String> tools = contextHolder.getTools();
        String prompt = contextHolder.getPrompt();

        long begin = System.currentTimeMillis();

        OpenAICompatibleChatService chatService = ApiProviderManage.getChatService(modelAlias);
        // 归一化副本不含系统提示词，此处统一补充
        List<ChatMessage> apiMessages = buildMessages(messages, prompt, tools);

        ApiRequestParams apiRequestParams = contextHolder.getRequestParams();
        if (tools != null && !tools.isEmpty()) {
            List<Tool> allFunctionTools = FCUtil.getAllFunctionTools(tools);
            apiRequestParams.setTools(allFunctionTools);
        }
        apiRequestParams.setMessages(apiMessages);

        // listener 为 null 时 getResult 内部自动 stream=false，走同步非流式请求
        String response;
        try {
            response = chatService.getResult(apiRequestParams, null, null);
        } catch (Exception e) {
            log.error("AI 调用失败", e);
            hasError.set(true);
            return;
        }

        JSONObject json = JSON.parseObject(response);

        // 解析AI返回内容
        String content = extractContent(json);
        List<ToolCall> toolCalls = extractToolCalls(json);

        // 将AI的响应加入历史
        ChatMessage assistantMessage;
        if (toolCalls.isEmpty()) {
            assistantMessage = ChatMessage.withAssistant(content);
        } else {
            assistantMessage = ChatMessage.withAssistant(toolCalls);
            if (StringUtils.isNotBlank(content)) {
                assistantMessage.setContent(Content.ofText(content));
            }
        }
        appendHistory(assistantMessage);

        log.info("AutoAgent【{}】第{}轮同步调用完成，{}个工具调用，耗时{}s",
                name, currentDeep, toolCalls.size(), (System.currentTimeMillis() - begin) / 1000.0);

        // 与 handleAIStream 的 onComplete 一致：本轮内立即执行工具调用
        if (!toolCalls.isEmpty()) {
            executeToolCalls(toolCalls);
        }
    }

    // ==================== 消息构建与归一化 ====================

    /**
     * 构建发送给 API 的消息列表
     * 发送 API 前的兜底格式归一化（生成副本，不修改原 history）
     *
     * @param resumeFromBreakpoint true：完整修复（补齐缺失 tool 结果、丢弃孤立/重复 tool 结果等），用于用户取消等终态场景；
     *                             false：仅基础清理（连续 user 去重、丢弃空 assistant 消息），
     *                             tool 消息原样保留以支持断点续跑
     */
    protected List<ChatMessage> normalizeMessages(boolean resumeFromBreakpoint) {

        List<ChatMessage> normalized = new ArrayList<>();

        List<ChatMessage> history = JsonMapper.getInstance()
                .fromJson(JsonMapper.toJsonString(contextHolder.getHistory()),
                        JsonMapper.getInstance().createCollectionType(ArrayList.class, ChatMessage.class));
        if (history == null || history.isEmpty()) {
            return normalized;
        }

        // 待补结果的工具调用：assistant.tool_calls 中尚未收到对应 tool 结果的部分（仅 resumeFromBreakpoint 模式使用）
        Map<String, ToolCall> pendingToolCalls = new LinkedHashMap<>();

        for (ChatMessage msg : history) {
            if (msg == null || StringUtils.isBlank(msg.getRole())) {
                continue;
            }
            String role = msg.getRole();

            // resumeFromBreakpoint 模式：tool 以外的消息打断了未收齐结果的工具调用时，先补齐缺失的 tool 结果
            if (resumeFromBreakpoint && !pendingToolCalls.isEmpty() && !ChatMessageType.TOOL.getRole().equals(role)) {
                for (ToolCall toolCall : pendingToolCalls.values()) {
                    String funcName = toolCall.getFunction() == null ? "" : toolCall.getFunction().getName();
                    normalized.add(ChatMessage.withTool("[系统消息] 工具调用 " + funcName + " 未返回结果", toolCall.getId()));
                }
                pendingToolCalls.clear();
            }

            // 任意位置的连续 user 消息仅保留最后一条 user
            if (ChatMessageType.USER.getRole().equals(role)) {
                if (!normalized.isEmpty() && ChatMessageType.USER.getRole()
                        .equals(normalized.get(normalized.size() - 1).getRole())) {
                    normalized.remove(normalized.size() - 1);
                }
                normalized.add(msg);
                continue;
            }

            if (ChatMessageType.TOOL.getRole().equals(role)) {
                if (resumeFromBreakpoint) {
                    String toolCallId = msg.getToolCallId();
                    if (StringUtils.isBlank(toolCallId) || !pendingToolCalls.containsKey(toolCallId)) {
                        // 孤立的 tool 结果（无对应 tool_call 或重复返回），丢弃
                        continue;
                    }
                    pendingToolCalls.remove(toolCallId);
                    if (msg.getContent() == null) {
                        msg.setContent(Content.ofText(""));
                    }
                }
                normalized.add(msg);
                continue;
            }

            if (ChatMessageType.ASSISTANT.getRole().equals(role)) {
                // 无内容且无工具调用的 assistant 消息（空回复残留），丢弃
                Content content = msg.getContent();
                if ((content == null || (StringUtils.isBlank(content.getText()) && (content.getMultiModals() == null
                        || content.getMultiModals().isEmpty()))) && (msg.getToolCalls() == null || msg.getToolCalls()
                        .isEmpty())) {
                    continue;
                }
                if (resumeFromBreakpoint && msg.getToolCalls() != null) {
                    for (ToolCall toolCall : msg.getToolCalls()) {
                        if (toolCall != null && StringUtils.isNotBlank(toolCall.getId())) {
                            pendingToolCalls.put(toolCall.getId(), toolCall);
                        }
                    }
                }
                normalized.add(msg);
                continue;
            }

            if (ChatMessageType.SYSTEM.getRole().equals(role)) {
                normalized.add(msg);
                continue;
            }

            // 未知角色丢弃，避免 API 报错
        }

        // 末尾残留未完成的工具调用（仅 resumeFromBreakpoint 模式修复）：
        if (resumeFromBreakpoint && !pendingToolCalls.isEmpty() && !normalized.isEmpty()
                && ChatMessageType.TOOL.getRole().equals(normalized.get(normalized.size() - 1).getRole())) {
            for (ToolCall toolCall : pendingToolCalls.values()) {
                String funcName = toolCall.getFunction() == null ? "" : toolCall.getFunction().getName();
                normalized.add(ChatMessage.withTool("[系统消息] 工具调用 " + funcName + " 未返回结果", toolCall.getId()));
            }
        }

        return normalized;
    }

    // ==================== 流式入口（循环实现）====================
    /**
     * 断点续行入口：不追加用户输入、不触发 ChatInput 事件，
     * 从 contextHolder 保存的断点状态（currentDeep / currentChatLoopRoundId / history 尾部）继续执行：
     * - 尾部为 user / 已完成的工具结果 -> 直接调 AI（NormalMessaeg / ToolsResultReady）
     * - 尾部为带 tool_calls 的 assistant（含部分结果）-> 先执行剩余工具再调 AI（ExecuteTools）
     * dispatch 循环、ChatEnd、异常兜底与持久化与 chatStream 完全一致（共用 runChatLoop）。
     */
    public CountDownLatch chatStreamResume() {
        streamChat = true;

        // 兜底：旧快照可能缺少 roundId
        if (StringUtils.isBlank(contextHolder.getCurrentChatLoopRoundId())) {
            contextHolder.setCurrentChatLoopRoundId(IdGen.uuid());
        }

        CountDownLatch finalLatch = new CountDownLatch(1);

        // ======  使用 AtomicBoolean 标记流式调用是否出错 ======
        AtomicBoolean hasError = new AtomicBoolean(false);

        // question 传 null：跳过 ChatInput 事件；currentDeep 不重置，从断点轮次继续
        runChatLoop(null, false, hasError, finalLatch);

        return finalLatch;
    }

    public CountDownLatch chatStream(String question) {
        return chatStream(question, true);
    }

    public CountDownLatch chatStream(String question, boolean user) {
        streamChat = true;
        // roundId 统一存放在 contextHolder（断点恢复后事件仍能还原原对话的 roundId）
        String roundId = IdGen.uuid();
        contextHolder.setCurrentDeep(1);
        contextHolder.setCurrentChatLoopRoundId(roundId);

        CountDownLatch finalLatch = new CountDownLatch(1);

        if (StringUtils.isBlank(question)) {
            question = "";
        }

        // 添加用户问题到历史
        ChatMessage chatMessage = ChatMessage.withUser(question);
        appendHistory(chatMessage);

        //  chatMessage 初始化完成

        // ======  使用 AtomicBoolean 标记流式调用是否出错 ======
        AtomicBoolean hasError = new AtomicBoolean(false);

        runChatLoop(question, user, hasError, finalLatch);

        return finalLatch;
    }

    /**
     * chatStream / chatStreamResume 共用的执行循环：ChatInput（可选）-> dispatch 循环 -> ChatEnd 三分支，
     * 异常兜底与统一持久化逻辑完全一致。
     *
     * @param question 非 null 时先触发 ChatInput 事件（chatStreamResume 续行无用户输入，传 null 跳过）
     * @param user     ChatInput 事件是否广播到 EventCenter（与 chatStream(question, user) 语义一致）
     */
    private void runChatLoop(String question, boolean user, AtomicBoolean hasError, CountDownLatch finalLatch) {
        ExecutorService executorService = ThreadPoolUtil.getExecutorService();

        executorService.execute(new Runnable() {
            @Override
            public void run() {
                EventCenter eventCenter = contextHolder.getEventCenter();
                EventHook eventHook = contextHolder.getEventHook();

                int currentDeep = contextHolder.getCurrentDeep();

                try {
                    if (question != null) {
                        ReplyId replyId = new ReplyId(historyId,
                                agentId,
                                contextHolder.getCurrentChatLoopRoundId(),
                                currentDeep,
                                IdGen.uuid(),
                                agentName);
                        if (contextHolder.getParentAgent() != null) {
                            replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                        }

                        ChatInputEvent event = new ChatInputEvent();
                        event.setQuestion(question);
                        event.setReplyId(replyId);
                        event.setContext(contextHolder);
                        if (eventHook != null) {
                            eventHook.onChatInput(event);
                        }
                        if (user && eventCenter != null) {
                            eventCenter.fireChatInput(event);
                        }
                    }

                    while (currentDeep <= maxDepth) {
                        log.info("AutoAgent【{}】流式第{}轮调用开始", name, currentDeep);

                        // ======  每轮开始前检查是否有上一轮的错误 ======
                        if (hasError.get()) {
                            log.error("AutoAgent【{}】检测到流式调用错误，中断循环", name);
                            break;
                        }

                        {
                            ReplyId replyId = new ReplyId(historyId,
                                    agentId,
                                    contextHolder.getCurrentChatLoopRoundId(),
                                    currentDeep,
                                    IdGen.uuid(),
                                    agentName);
                            if (contextHolder.getParentAgent() != null) {
                                replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                            }

                            StepBeginEvent event = new StepBeginEvent();
                            event.setName(name);
                            event.setDeep(currentDeep);
                            event.setReplyId(replyId);
                            event.setContext(contextHolder);
                            if (eventHook != null) {
                                eventHook.onStepBegin(event);
                            }
                            if (eventCenter != null) {
                                eventCenter.fireStepBegin(event);
                            }
                        }

                        // 调用流式AI并等待完成
                        dispatch(false, hasError);

                        // ======  返回后立即检查错误标志 ======
                        if (hasError.get()) {
                            log.error("AutoAgent【{}】第{}轮流式调用出错，中断循环", name, currentDeep);
                            break;
                        }

                        {
                            ReplyId replyId = new ReplyId(historyId,
                                    agentId,
                                    contextHolder.getCurrentChatLoopRoundId(),
                                    currentDeep,
                                    IdGen.uuid(),
                                    agentName);
                            if (contextHolder.getParentAgent() != null) {
                                replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                            }

                            StepEndEvent event = new StepEndEvent();
                            event.setName(name);
                            event.setDeep(currentDeep);
                            event.setReplyId(replyId);
                            event.setContext(contextHolder);
                            if (eventHook != null) {
                                eventHook.onStepEnd(event);
                            }
                            if (eventCenter != null) {
                                eventCenter.fireStepEnd(event);
                            }
                        }

                        log.info("AutoAgent【{}】流式第{}轮调用完成", name, currentDeep);

                        // 检查是否需要继续（根据是否有工具调用）
                        List<ChatMessage> history = contextHolder.getHistory();
                        if (history.size() == 0) {
                            break;
                        }
                        ChatMessage lastAssistantMsg = history.get(history.size() - 1);
                        String role = lastAssistantMsg.getRole();
                        if (!"tool".equals(role)) {

                            if (StringUtils.isBlank(lastAssistantMsg.getContent().getText())) {
                                history.remove(lastAssistantMsg);
                            }

                            break;
                        }

                        currentDeep++;
                        contextHolder.setCurrentDeep(currentDeep);
                    }

                    // ======  区分因错误中断和因深度限制中断 ======
                    if (hasError.get()) {
                        // 因流式调用错误而中断
                        String errorMsg = "Agent 流式调用发生错误，已中断执行。";
                        log.error(errorMsg);

                        {
                            ReplyId replyId = new ReplyId(historyId,
                                    agentId,
                                    contextHolder.getCurrentChatLoopRoundId(),
                                    currentDeep,
                                    IdGen.uuid(),
                                    agentName);
                            if (contextHolder.getParentAgent() != null) {
                                replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                            }

                            ChatEndEvent event = new ChatEndEvent();
                            event.setSuccess(false);
                            event.setException(new RuntimeException(errorMsg));
                            event.setReplyId(replyId);
                            event.setContext(contextHolder);
                            if (eventHook != null) {
                                eventHook.onChatEnd(event);
                            }
                            if (eventCenter != null) {
                                eventCenter.fireChatEnd(event);
                            }
                        }

                        // 补全 assistant 消息，避免 history 以 user 结尾
                        ensureTrailingAssistant(errorMsg);

                    } else if (currentDeep > maxDepth) {
                        String errorMsg = "Agent 执行轮数超过最大限制 " + maxDepth + "，请检查工具调用是否陷入循环。";
                        log.error(errorMsg);

                        {
                            ReplyId replyId = new ReplyId(historyId,
                                    agentId,
                                    contextHolder.getCurrentChatLoopRoundId(),
                                    currentDeep,
                                    IdGen.uuid(),
                                    agentName);
                            if (contextHolder.getParentAgent() != null) {
                                replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                            }

                            ChatEndEvent event = new ChatEndEvent();
                            event.setSuccess(false);
                            event.setException(new RuntimeException(errorMsg));
                            event.setReplyId(replyId);
                            event.setContext(contextHolder);
                            if (eventHook != null) {
                                eventHook.onChatEnd(event);
                            }
                            if (eventCenter != null) {
                                eventCenter.fireChatEnd(event);
                            }
                        }

                        // 补全 assistant 消息，避免 history 以 user 结尾
                        ensureTrailingAssistant(errorMsg);

                    } else {

                        {
                            ReplyId replyId = new ReplyId(historyId,
                                    agentId,
                                    contextHolder.getCurrentChatLoopRoundId(),
                                    currentDeep,
                                    IdGen.uuid(),
                                    agentName);
                            if (contextHolder.getParentAgent() != null) {
                                replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                            }

                            ChatEndEvent event = new ChatEndEvent();
                            event.setSuccess(true);
                            event.setException(null);
                            event.setReplyId(replyId);
                            event.setContext(contextHolder);
                            if (eventHook != null) {
                                eventHook.onChatEnd(event);
                            }
                            if (eventCenter != null) {
                                eventCenter.fireChatEnd(event);
                            }
                        }
                    }

                } catch (Exception e) {
                    log.error("流式处理异常", e);

                    {
                        ReplyId replyId = new ReplyId(historyId,
                                agentId,
                                contextHolder.getCurrentChatLoopRoundId(),
                                currentDeep,
                                IdGen.uuid(),
                                agentName);
                        if (contextHolder.getParentAgent() != null) {
                            replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                        }

                        ChatEndEvent event = new ChatEndEvent();
                        event.setSuccess(false);
                        event.setException(e);
                        event.setReplyId(replyId);
                        event.setContext(contextHolder);

                        if (eventHook != null) {
                            eventHook.onChatEnd(event);
                        }
                        if (eventCenter != null) {
                            eventCenter.fireChatEnd(event);
                        }
                    }

                    // 补全 assistant 消息，避免 history 以 user 结尾
                    ensureTrailingAssistant("流式处理异常: " + e.getMessage());

                } finally {
                    // 统一持久化：无论正常/异常/深度超限，都在 finally 中执行持久化
                    try {
                        persistNewMessages();
                    } catch (Exception pe) {
                        log.error("流式持久化失败", pe);
                    }
                    finalLatch.countDown();
                }
            }
        });
    }

    protected void executeToolCalls(List<ToolCall> toolCalls) {
        int currentDeep = contextHolder.getCurrentDeep();
        EventCenter eventCenter = contextHolder.getEventCenter();
        EventHook eventHook = contextHolder.getEventHook();

        Set<String> executedToolSignatures = new HashSet<>();

        for (ToolCall toolCall : toolCalls) {
            String functionName = toolCall.getFunction().getName();
            String arguments = toolCall.getFunction().getArguments();

            // 生成工具调用签名，用于检测循环
            String signature = functionName + ":" + arguments;
            if (executedToolSignatures.contains(signature)) {
                String errorMsg = "检测到重复的工具调用: " + signature + "，已跳过。";
                log.warn(errorMsg);
                appendHistory(ChatMessage.withTool(errorMsg, toolCall.getId()));
                continue;
            }
            executedToolSignatures.add(signature);

            String toolId = md5Hex(signature + System.currentTimeMillis()).toLowerCase();

            // 回调工具开始消息
            {
                ReplyId replyId = new ReplyId(historyId,
                        agentId,
                        contextHolder.getCurrentChatLoopRoundId(),
                        currentDeep,
                        IdGen.uuid(),
                        agentName);
                if (contextHolder.getParentAgent() != null) {
                    replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                }

                CallToolBeginEvent event = new CallToolBeginEvent();
                event.setToolCall(toolCall);
                event.setToolId(toolId);
                event.setReplyId(replyId);
                event.setContext(contextHolder);

                if (eventHook != null) {
                    eventHook.onCallToolBegin(event);
                }
                if (eventCenter != null) {
                    eventCenter.fireCallToolBegin(event);
                }
            }

            try {

                String toolMsgId = "ToolMsg_" + IdGen.uuidShort();

                boolean caneUse = false;
                List<String> applyTools = contextHolder.getTools();
                for (String applyToolName : applyTools) {
                    if (StringUtils.equalsIgnoreCase(applyToolName, functionName)) {
                        caneUse = true;
                        break;
                    }
                }

                String result;
                if (!caneUse) {
                    result = "工具不存在：" + functionName;
                } else {
                    result = FCUtil.invoke(functionName, arguments, contextHolder);
                }

                appendHistory(ChatMessage.withTool(toolMsgId + "\n\n" + result, toolCall.getId()));

                contextHolder.getTopGlobalVariables().put(toolMsgId, result);

                String endMsg = String.format("工具调用成功: %s -> %s", functionName, result);
                log.info(endMsg);

                // 回调工具结束消息
                {
                    ReplyId replyId = new ReplyId(historyId, agentId, contextHolder.getCurrentChatLoopRoundId(), currentDeep, IdGen.uuid(), agentName);
                    if (contextHolder.getParentAgent() != null) {
                        replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                    }

                    CallToolEndEvent event = new CallToolEndEvent();
                    event.setToolCall(toolCall);
                    event.setToolId(toolId);
                    event.setEndMsg(endMsg);
                    event.setReplyId(replyId);
                    event.setContext(contextHolder);
                    if (eventHook != null) {
                        eventHook.onCallToolEnd(event);
                    }
                    if (eventCenter != null) {
                        eventCenter.fireCallToolEnd(event);
                    }
                }

            } catch (Exception e) {

                String endMsg = "工具调用失败: " + functionName;

                log.error(endMsg, e);
                String errorMsg = "工具调用失败，错误信息: " + e.getMessage();
                appendHistory(ChatMessage.withTool(errorMsg, toolCall.getId()));

                // 回调工具结束消息
                {
                    ReplyId replyId = new ReplyId(historyId, agentId, contextHolder.getCurrentChatLoopRoundId(), currentDeep, IdGen.uuid(), agentName);
                    if (contextHolder.getParentAgent() != null) {
                        replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                    }

                    CallToolEndEvent event = new CallToolEndEvent();
                    event.setToolCall(toolCall);
                    event.setToolId(toolId);
                    event.setEndMsg(endMsg);
                    event.setReplyId(replyId);
                    event.setContext(contextHolder);

                    if (eventHook != null) {
                        eventHook.onCallToolEnd(event);
                    }
                    if (eventCenter != null) {
                        eventCenter.fireCallToolEnd(event);
                    }
                }
            }
        }
    }

    // ========  辅助方法  ===========

    /**
     * 构建消息列表（包含系统提示）
     */
    protected List<ChatMessage> buildMessages(List<ChatMessage> history, String prompt, List<String> tools) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StringUtils.isNotBlank(prompt)) {
            messages.add(ChatMessage.withSystem(prompt));
        }
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }
        return messages;
    }

    /**
     * 从JSON响应中提取content（同步非流式响应解析）
     */
    private String extractContent(JSONObject json) {
        Object result = JSONPath.eval(json, "$.choices[0].delta.content");
        if (result == null) {
            result = JSONPath.eval(json, "$.choices[0].message.content");
        }
        return result != null ? result.toString() : null;
    }

    /**
     * 从JSON响应中提取tool_calls（同步非流式响应解析）
     */
    private List<ToolCall> extractToolCalls(JSONObject json) {
        JavaType collectionType = JsonMapper.getInstance()
                .createCollectionType(ArrayList.class, ToolCall.class);
        Object deltaCalls = JSONPath.eval(json, "$.choices[0].delta.tool_calls");
        if (deltaCalls instanceof JSONArray && !((JSONArray) deltaCalls).isEmpty()) {
            return JsonMapper.getInstance()
                    .fromJson(JsonMapper.toJsonString(deltaCalls), collectionType);
        }
        Object messageCalls = JSONPath.eval(json, "$.choices[0].message.tool_calls");
        if (messageCalls instanceof JSONArray && !((JSONArray) messageCalls).isEmpty()) {
            return JsonMapper.getInstance()
                    .fromJson(JsonMapper.toJsonString(messageCalls), collectionType);
        }
        return new ArrayList<>();
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5计算失败", e);
        }
    }

    /**
     * 确保 history 末尾是 assistant 消息
     * 异常终止时调用，若末尾非 assistant 则补全一条 assistant 错误消息
     */
    private void ensureTrailingAssistant(String errorMsg) {
        List<ChatMessage> history = contextHolder.getHistory();
        if (history.isEmpty()) {
            return;
        }
        ChatMessage lastMsg = history.get(history.size() - 1);
        if (!"assistant".equals(lastMsg.getRole())) {
            appendHistory(ChatMessage.withAssistant(errorMsg));
        }
    }

    /**
     * 添加消息到 history，同时记录到持久化队列
     * 所有需要新增消息的地方统一走此方法，替代直接 addHistory/history.add
     */
    private void appendHistory(ChatMessage message) {
        contextHolder.addHistory(message);
        appendChatMessageHistoryQueue.offer(message);
    }

    /**
     * 持久化队列中累积的新增消息，持久化后清空队列
     * 会过滤掉空内容且无工具调动的消息（可能已被 history.remove 移除）
     */
    private void persistNewMessages() {
        if (historyPersist == null || appendChatMessageHistoryQueue.isEmpty()) {
            return;
        }
        List<ChatMessage> newMessages = new ArrayList<>();
        for (ChatMessage msg : appendChatMessageHistoryQueue) {
            // 跳过空内容且无工具调用的消息（已被 history.remove 移除，不应持久化）
            boolean emptyContent = msg.getContent() == null || StringUtils.isBlank(msg.getContent().getText());
            boolean noToolCalls = msg.getToolCalls() == null || msg.getToolCalls().isEmpty();
            if (emptyContent && noToolCalls) {
                continue;
            }
            newMessages.add(msg);
        }
        if (!newMessages.isEmpty()) {
            historyPersist.saveChatMessages(newMessages, contextHolder);
        }
        appendChatMessageHistoryQueue.clear();
    }

    // ==================== 同步入口（循环实现）====================

    /**
     * 同步 chat 入口：在调用者线程直接推进 dispatch 循环直到得到最终回复（阻塞直至结束）。
     * 底层发送 stream=false 的同步 API 请求（handleAISync），与流式（SSE）机制不混用；
     * 主体事件（ChatInput/StepBegin/StepEnd/ChatEnd）与异常传播和流式模式（chatStream）保持一致，
     * 区别仅在于：无流式增量 General 事件、返回值直接携带末轮 assistant 回复文本或错误信息。
     */
    public String chat(String question) {
        streamChat = false;
        // 新一轮对话：重置断点状态（roundId 供本轮事件回执使用，与 chatStream 一致）
        contextHolder.setCurrentDeep(1);
        contextHolder.setCurrentChatLoopRoundId(IdGen.uuid());

        if (StringUtils.isBlank(question)) {
            question = "";
        }

        // 添加用户问题到历史（history 合法性由 dispatch 内的 normalizeMessages 归一化兜底）
        ChatMessage chatMessage = ChatMessage.withUser(question);
        appendHistory(chatMessage);

        // ======  使用 AtomicBoolean 标记调用是否出错 ======
        AtomicBoolean hasError = new AtomicBoolean(false);

        return runChatLoopSync(question, true, hasError);
    }

    /**
     * 同步断点续行入口（chat 的 resume 对应物）：
     * 不追加用户输入、不触发 ChatInput 事件，从 contextHolder 保存的断点状态
     * （currentDeep / currentChatLoopRoundId / history 尾部）继续执行；
     * 底层发送 stream=false 的同步 API 请求（handleAISync），与流式机制不混用。
     * 其余主体事件（StepBegin/StepEnd/ChatEnd）、异常传播与持久化和 chat 完全一致（共用 runChatLoopSync）。
     */
    public String chatSyncResume() {
        streamChat = false;

        // 兜底：旧快照可能缺少 roundId
        if (StringUtils.isBlank(contextHolder.getCurrentChatLoopRoundId())) {
            contextHolder.setCurrentChatLoopRoundId(IdGen.uuid());
        }

        // ======  使用 AtomicBoolean 标记调用是否出错 ======
        AtomicBoolean hasError = new AtomicBoolean(false);

        // question 传 null：跳过 ChatInput 事件；currentDeep 不重置，从断点轮次继续
        return runChatLoopSync(null, false, hasError);
    }

    /**
     * chat / chatSyncResume 共用的同步执行循环：ChatInput（可选）-> StepBegin -> dispatch -> StepEnd 循环
     * -> ChatEnd 三分支，主体事件与异常传播和流式模式（runChatLoop）保持一致；
     * 区别仅在：调用者线程同步执行、无 finalLatch、返回末轮 assistant 回复文本或错误信息。
     *
     * @param question 非 null 时先触发 ChatInput 事件（chatSyncResume 续行无用户输入，传 null 跳过）
     * @param user     ChatInput 事件是否广播到 EventCenter（与 chatStream(question, user) 语义一致）
     * @param hasError 跨轮错误标志（由 dispatch 内部上报）
     * @return 末轮 assistant 回复文本，或异常/错误/深度超限时的错误信息
     */
    private String runChatLoopSync(String question, boolean user, AtomicBoolean hasError) {
        EventCenter eventCenter = contextHolder.getEventCenter();
        EventHook eventHook = contextHolder.getEventHook();

        int currentDeep = contextHolder.getCurrentDeep();
        String lastContent = null;
        String errorMessage = null;

        try {
            if (question != null) {
                ReplyId replyId = new ReplyId(historyId,
                        agentId,
                        contextHolder.getCurrentChatLoopRoundId(),
                        currentDeep,
                        IdGen.uuid(),
                        agentName);
                if (contextHolder.getParentAgent() != null) {
                    replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                }

                ChatInputEvent event = new ChatInputEvent();
                event.setQuestion(question);
                event.setReplyId(replyId);
                event.setContext(contextHolder);
                if (eventHook != null) {
                    eventHook.onChatInput(event);
                }
                if (user && eventCenter != null) {
                    eventCenter.fireChatInput(event);
                }
            }

            while (currentDeep <= maxDepth) {
                log.info("AutoAgent【{}】同步第{}轮调用开始", name, currentDeep);

                // ======  每轮开始前检查是否有上一轮的错误 ======
                if (hasError.get()) {
                    log.error("AutoAgent【{}】检测到同步调用错误，中断循环", name);
                    break;
                }

                {
                    ReplyId replyId = new ReplyId(historyId,
                            agentId,
                            contextHolder.getCurrentChatLoopRoundId(),
                            currentDeep,
                            IdGen.uuid(),
                            agentName);
                    if (contextHolder.getParentAgent() != null) {
                        replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                    }

                    StepBeginEvent event = new StepBeginEvent();
                    event.setName(name);
                    event.setDeep(currentDeep);
                    event.setReplyId(replyId);
                    event.setContext(contextHolder);
                    if (eventHook != null) {
                        eventHook.onStepBegin(event);
                    }
                    if (eventCenter != null) {
                        eventCenter.fireStepBegin(event);
                    }
                }

                // 同步推进一轮（stream=false，含工具执行）
                dispatch(false, hasError);

                // ======  返回后立即检查错误标志 ======
                if (hasError.get()) {
                    log.error("AutoAgent【{}】第{}轮同步调用出错，中断循环", name, currentDeep);
                    break;
                }

                {
                    ReplyId replyId = new ReplyId(historyId,
                            agentId,
                            contextHolder.getCurrentChatLoopRoundId(),
                            currentDeep,
                            IdGen.uuid(),
                            agentName);
                    if (contextHolder.getParentAgent() != null) {
                        replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                    }

                    StepEndEvent event = new StepEndEvent();
                    event.setName(name);
                    event.setDeep(currentDeep);
                    event.setReplyId(replyId);
                    event.setContext(contextHolder);
                    if (eventHook != null) {
                        eventHook.onStepEnd(event);
                    }
                    if (eventCenter != null) {
                        eventCenter.fireStepEnd(event);
                    }
                }

                log.info("AutoAgent【{}】同步第{}轮调用完成", name, currentDeep);

                // 检查是否需要继续（根据尾部是否有待处理的工具结果）
                List<ChatMessage> history = contextHolder.getHistory();
                if (history.size() == 0) {
                    break;
                }
                ChatMessage lastAssistantMsg = history.get(history.size() - 1);
                String role = lastAssistantMsg.getRole();
                if (!"tool".equals(role)) {

                    // 同步返回值需要：提取末条 assistant 回复作为答案
                    if (lastAssistantMsg.getContent() != null) {
                        lastContent = lastAssistantMsg.getContent().getText();
                    }
                    if (StringUtils.isBlank(lastContent)) {
                        history.remove(lastAssistantMsg);
                    }

                    break;
                }

                currentDeep++;
                contextHolder.setCurrentDeep(currentDeep);
            }

            // ======  区分因错误中断和因深度限制中断 ======
            if (hasError.get()) {
                // 因调用错误而中断
                errorMessage = "Agent 同步调用发生错误，已中断执行。";
                log.error(errorMessage);

                {
                    ReplyId replyId = new ReplyId(historyId,
                            agentId,
                            contextHolder.getCurrentChatLoopRoundId(),
                            currentDeep,
                            IdGen.uuid(),
                            agentName);
                    if (contextHolder.getParentAgent() != null) {
                        replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                    }

                    ChatEndEvent event = new ChatEndEvent();
                    event.setSuccess(false);
                    event.setException(new RuntimeException(errorMessage));
                    event.setReplyId(replyId);
                    event.setContext(contextHolder);
                    if (eventHook != null) {
                        eventHook.onChatEnd(event);
                    }
                    if (eventCenter != null) {
                        eventCenter.fireChatEnd(event);
                    }
                }

                // 补全 assistant 消息，避免 history 以 user 结尾
                ensureTrailingAssistant(errorMessage);

            } else if (currentDeep > maxDepth) {
                errorMessage = "Agent 执行轮数超过最大限制 " + maxDepth + "，请检查工具调用是否陷入循环。";
                log.error(errorMessage);

                {
                    ReplyId replyId = new ReplyId(historyId,
                            agentId,
                            contextHolder.getCurrentChatLoopRoundId(),
                            currentDeep,
                            IdGen.uuid(),
                            agentName);
                    if (contextHolder.getParentAgent() != null) {
                        replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                    }

                    ChatEndEvent event = new ChatEndEvent();
                    event.setSuccess(false);
                    event.setException(new RuntimeException(errorMessage));
                    event.setReplyId(replyId);
                    event.setContext(contextHolder);
                    if (eventHook != null) {
                        eventHook.onChatEnd(event);
                    }
                    if (eventCenter != null) {
                        eventCenter.fireChatEnd(event);
                    }
                }

                // 补全 assistant 消息，避免 history 以 user 结尾
                ensureTrailingAssistant(errorMessage);

            } else {

                {
                    ReplyId replyId = new ReplyId(historyId,
                            agentId,
                            contextHolder.getCurrentChatLoopRoundId(),
                            currentDeep,
                            IdGen.uuid(),
                            agentName);
                    if (contextHolder.getParentAgent() != null) {
                        replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                    }

                    ChatEndEvent event = new ChatEndEvent();
                    event.setSuccess(true);
                    event.setException(null);
                    event.setReplyId(replyId);
                    event.setContext(contextHolder);
                    if (eventHook != null) {
                        eventHook.onChatEnd(event);
                    }
                    if (eventCenter != null) {
                        eventCenter.fireChatEnd(event);
                    }
                }
            }

        } catch (Exception e) {
            log.error("同步处理异常", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            errorMessage = "同步处理异常: " + e.getMessage();

            {
                ReplyId replyId = new ReplyId(historyId,
                        agentId,
                        contextHolder.getCurrentChatLoopRoundId(),
                        currentDeep,
                        IdGen.uuid(),
                        agentName);
                if (contextHolder.getParentAgent() != null) {
                    replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                }

                ChatEndEvent event = new ChatEndEvent();
                event.setSuccess(false);
                event.setException(e);
                event.setReplyId(replyId);
                event.setContext(contextHolder);

                if (eventHook != null) {
                    eventHook.onChatEnd(event);
                }
                if (eventCenter != null) {
                    eventCenter.fireChatEnd(event);
                }
            }

            // 补全 assistant 消息，避免 history 以 user 结尾
            ensureTrailingAssistant(errorMessage);

        } finally {
            // 统一持久化：无论正常/异常/深度超限，都在 finally 中执行持久化
            try {
                persistNewMessages();
            } catch (Exception pe) {
                log.error("同步持久化失败", pe);
            }
        }

        return errorMessage != null ? errorMessage : lastContent;
    }
}
