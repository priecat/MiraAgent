package net.itzq.mira.modules.ai.agent;

import java.security.MessageDigest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONPath;
import com.fasterxml.jackson.databind.JavaType;
import lombok.Data;
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
import net.itzq.mira.modules.ai.client.openai.tool.Tool;
import net.itzq.mira.modules.ai.client.openai.tool.ToolCall;
import net.itzq.mira.modules.ai.client.tool.FCUtil;
import net.itzq.mira.modules.ai.entity.chat.ReplyId;
import net.itzq.mira.modules.ai.persistence.AbstractHistoryPersist;
import net.itzq.mira.modules.ai.utils.ThreadPoolUtil;
import org.apache.commons.lang3.StringUtils;

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
@Data
public class BasicAgent implements IBasicAgent {

    private int maxDepth = 999; // 最大循环次数

    private AgentContextHolder contextHolder;
    private String name;
    private String agentName;
    private String agentSrcId;
    private String agentId;
    private String historyId;
    private boolean mainAgent = false;

    private int timeout = 3600; // s

    private boolean streamChat = false;

    private AbstractHistoryPersist historyPersist = null;

    private final Queue<ChatMessage> appendChatMessageHistoryQueue = new ConcurrentLinkedQueue<>();

    public BasicAgent(AgentContextHolder contextHolder) {
        this(contextHolder, IdGen.uuidShort(), false);
    }

    public BasicAgent(AgentContextHolder contextHolder, String name) {
        this(contextHolder, name, false);
    }

    public BasicAgent(AgentContextHolder contextHolder, String name, boolean main) {

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


    // ==================== 同步入口（循环实现）====================
    public String chat(String question) {

        // 对话前校验 history 合法性，移除上次异常残留的孤立消息，避免连续 user
        ensureHistoryValid();

        if (StringUtils.isBlank(question)) {
            question = "";
        }
        streamChat = false;
        // 添加用户问题到历史
        ChatMessage chatMessage = ChatMessage.withUser(question);
        appendHistory(chatMessage);

        // 当前深度
        int currentDeep = 1;
        String lastContent = null;

        // ======  用于标记循环是否因异常而中断 ======
        boolean errorBreak = false;
        String errorMessage = null;

        while (currentDeep <= maxDepth) {
            log.info("AutoAgent【{}】开始第{}轮调用", name, currentDeep);

            long begin = System.currentTimeMillis();

            // ======   捕获 callAI 异常，中断循环 ======
            List<ToolCall> toolCalls;
            try {
                toolCalls = callAI(currentDeep);
            } catch (Exception e) {
                log.error("AutoAgent【{}】第{}轮AI调用异常，中断循环", name, currentDeep, e);
                errorBreak = true;
                errorMessage = "AI 服务异常: " + e.getMessage();
                break;
            }

            long end = System.currentTimeMillis();
            double time = (end - begin) / 1000.0;

            // 如果没有工具调用，返回AI的回复内容
            if (toolCalls.isEmpty()) {
                // 从历史中获取最后一次AI回复
                List<ChatMessage> history = contextHolder.getHistory();
                if (history.size() == 0) {
                    break;
                }
                ChatMessage lastMessage = history.get(history.size() - 1);
                if (lastMessage != null && lastMessage.getContent() != null) {
                    lastContent = lastMessage.getContent().getText();
                }
                if (StringUtils.isBlank(lastContent)) {
                    history.remove(lastMessage);
                }

                log.info("AutoAgent【{}】第{}轮完成，无工具调用，耗时{}s", name, currentDeep, time);
                break;
            }

            log.info("AutoAgent【{}】第{}轮返回{}个工具调用，耗时{}s ", name, currentDeep, toolCalls.size(), time);

            // 执行工具调用
            executeToolCalls(toolCalls, null, null, currentDeep);

            currentDeep++;
        }

        // ======  异常或深度超限时补全 assistant 消息，避免 history 以 user 结尾 ======
        if (errorBreak) {
            log.error("AutoAgent【{}】因AI调用异常中断: {}", name, errorMessage);
            appendHistory(ChatMessage.withAssistant(errorMessage));
        } else if (currentDeep > maxDepth) {
            errorMessage = "Agent 执行轮数超过最大限制 " + maxDepth + "，请检查工具调用是否陷入循环。";
            log.error(errorMessage);
            appendHistory(ChatMessage.withAssistant(errorMessage));
        }

        // ======  统一持久化：无论正常/异常/深度超限，都执行持久化 ======
        persistNewMessages();

        return (errorBreak || currentDeep > maxDepth) ? errorMessage : lastContent;
    }

    /**
     * 调用AI并返回工具调用列表
     */
    private List<ToolCall> callAI(int currentDeep) {
        String modelAlias = contextHolder.getModelAlias();
        if (StringUtils.isBlank(modelAlias)) {
            modelAlias = ApiProviderManage.getDefaultModel();
        }
        List<String> tools = contextHolder.getTools();
        String prompt = contextHolder.getPrompt();
        List<ChatMessage> history = contextHolder.getHistory();

        OpenAICompatibleChatService chatService = ApiProviderManage.getChatService(modelAlias);
        List<ChatMessage> messages = buildMessages(history, prompt, tools);

        ApiRequestParams apiRequestParams = contextHolder.getRequestParams();
        if (tools != null && !tools.isEmpty()) {
            List<Tool> allFunctionTools = FCUtil.getAllFunctionTools(tools);
            apiRequestParams.setTools(allFunctionTools);
        }
        apiRequestParams.setMessages(messages);

        String response;
        try {
            response = chatService.getResult(apiRequestParams, null, null);
        } catch (Exception e) {
            log.error("AI 调用失败", e);
            throw new RuntimeException(e);
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

        return toolCalls;
    }

    /**
     * 执行工具调用（串行）
     */
    protected void executeToolCalls(List<ToolCall> toolCalls, String roundId, StreamEventHandler handler,
            Integer currentDeep) {
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
                ReplyId replyId = new ReplyId(historyId, agentId, roundId, currentDeep, IdGen.uuid(), agentName);
                if (contextHolder.getParentAgent() != null) {
                    replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                }

                CallToolBeginEvent event = new CallToolBeginEvent();
                event.setToolCall(toolCall);
                event.setToolId(toolId);
                event.setHandler(handler);
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
                    ReplyId replyId = new ReplyId(historyId, agentId, roundId, currentDeep, IdGen.uuid(), agentName);
                    if (contextHolder.getParentAgent() != null) {
                        replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                    }

                    CallToolEndEvent event = new CallToolEndEvent();
                    event.setToolCall(toolCall);
                    event.setToolId(toolId);
                    event.setEndMsg(endMsg);
                    event.setHandler(handler);
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
                    ReplyId replyId = new ReplyId(historyId, agentId, roundId, currentDeep, IdGen.uuid(), agentName);
                    if (contextHolder.getParentAgent() != null) {
                        replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                    }

                    CallToolEndEvent event = new CallToolEndEvent();
                    event.setToolCall(toolCall);
                    event.setToolId(toolId);
                    event.setEndMsg(endMsg);
                    event.setHandler(handler);
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

    // ==================== 流式入口（循环实现）====================
    public CountDownLatch chatStream(String question) {
        return chatStream(question, true);
    }

    public CountDownLatch chatStream(String question, boolean user) {
        streamChat = true;
        String roundId = IdGen.uuid();

        // 对话前校验 history 合法性，移除上次异常残留的孤立消息，避免连续 user
        ensureHistoryValid();

        CountDownLatch finalLatch = new CountDownLatch(1);

        if (StringUtils.isBlank(question)) {
            question = "";
        }
        String finalQuestion = question;

        // 添加用户问题到历史
        ChatMessage chatMessage = ChatMessage.withUser(question);
        appendHistory(chatMessage);

        ExecutorService executorService = ThreadPoolUtil.getExecutorService();

        // ======  使用 AtomicBoolean 标记流式调用是否出错 ======
        AtomicBoolean hasError = new AtomicBoolean(false);

        executorService.execute(new Runnable() {
            @Override
            public void run() {
                EventCenter eventCenter = contextHolder.getEventCenter();
                EventHook eventHook = contextHolder.getEventHook();

                int currentDeep = 1;

                {
                    ReplyId replyId = new ReplyId(historyId, agentId, roundId, currentDeep, IdGen.uuid(), agentName);
                    if (contextHolder.getParentAgent() != null) {
                        replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
                    }

                    ChatInputEvent event = new ChatInputEvent();
                    event.setQuestion(finalQuestion);
                    event.setReplyId(replyId);
                    event.setContext(contextHolder);
                    if (eventHook != null) {
                        eventHook.onChatInput(event);
                    }
                    if (user && eventCenter != null) {
                        eventCenter.fireChatInput(event);
                    }
                }

                try {
                    CountDownLatch roundLatch = null;
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
                                    roundId,
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
                        roundLatch = chatStreamRound(currentDeep, roundId, hasError);
                        roundLatch.await(timeout, TimeUnit.SECONDS);

                        // ======  返回后立即检查错误标志 ======
                        if (hasError.get()) {
                            log.error("AutoAgent【{}】第{}轮流式调用出错，中断循环", name, currentDeep);
                            break;
                        }

                        {
                            ReplyId replyId = new ReplyId(historyId,
                                    agentId,
                                    roundId,
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
                    }

                    // ======  区分因错误中断和因深度限制中断 ======
                    if (hasError.get()) {
                        // 因流式调用错误而中断
                        String errorMsg = "Agent 流式调用发生错误，已中断执行。";
                        log.error(errorMsg);

                        {
                            ReplyId replyId = new ReplyId(historyId,
                                    agentId,
                                    roundId,
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
                                    roundId,
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
                                    roundId,
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
                                roundId,
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

        return finalLatch;
    }

    /**
     * 单轮流式调用
     */
    private CountDownLatch chatStreamRound(int currentDeep, String roundId, AtomicBoolean hasError) {
        String msgId = IdGen.uuid();

        CountDownLatch roundLatch = new CountDownLatch(1);

        String modelAlias = contextHolder.getModelAlias();
        if (StringUtils.isBlank(modelAlias)) {
            modelAlias = ApiProviderManage.getDefaultModel();
        }
        List<String> tools = contextHolder.getTools();
        String prompt = contextHolder.getPrompt();
        List<ChatMessage> history = contextHolder.getHistory();

        long begin = System.currentTimeMillis();

        OpenAICompatibleChatService chatService = ApiProviderManage.getChatService(modelAlias);
        List<ChatMessage> messages = buildMessages(history, prompt, tools);

        ApiRequestParams apiRequestParams = contextHolder.getRequestParams();
        if (tools != null && !tools.isEmpty()) {
            List<Tool> allFunctionTools = FCUtil.getAllFunctionTools(tools);
            apiRequestParams.setTools(allFunctionTools);
        }
        apiRequestParams.setMessages(messages);

        EventCenter eventCenter = contextHolder.getEventCenter();
        EventHook eventHook = contextHolder.getEventHook();

        SseEventListener listener = new SseEventListener() {
            @Override
            public void onEvent(String eventType, String data, String id, StreamEventHandler handler) {
                log.debug("Stream event: {}", handler.getCurrStr());

                {
                    ReplyId replyId = new ReplyId(historyId, agentId, roundId, currentDeep, msgId, agentName);
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

                        // 执行工具调用
                        executeToolCalls(toolCalls, roundId, handler, currentDeep);
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
                    roundLatch.countDown();
                }
            }

            @Override
            public void onError(Throwable t, StreamEventHandler handler) {
                log.error("流式处理出错", t);

                // ======  设置错误标志，使外层循环能够感知并中断 ======
                hasError.set(true);

                ReplyId replyId = new ReplyId(historyId, agentId, roundId, currentDeep, msgId, agentName);
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

                roundLatch.countDown();
            }
        };

        try {
            chatService.getResult(apiRequestParams, null, listener);
        } catch (Exception e) {
            log.error("流式请求失败", e);

            hasError.set(true);
            roundLatch.countDown(); // 确保 latch 被释放，避免外层永远阻塞
            // 不再抛出异常，让外层通过 hasError 标志来处理
        }

        return roundLatch;
    }

    // ==================== 工具方法 ====================

    /**
     * 校验并修复 history 合法性
     * 主要处理异常终止后残留的非法消息序列，避免下次对话出现连续 user 等问题：
     * - 移除末尾孤立的 user 消息（上次异常未得到 assistant 回复）
     * - 为末尾带 tool_calls 但缺少 tool 结果的 assistant 补充错误 tool 消息
     */
    private void ensureHistoryValid() {
        List<ChatMessage> history = contextHolder.getHistory();
        if (!history.isEmpty()) {
            ChatMessage lastMsg = history.get(history.size() - 1);
            String lastRole = lastMsg.getRole();

            if ("user".equals(lastRole)) {
                // 末尾是 user，说明上次异常终止未得到 assistant 回复，移除避免连续 user
                log.warn("AutoAgent【{}】移除 history 末尾孤立的 user 消息（上次异常残留）", name);
                appendHistory(ChatMessage.withAssistant("[系统消息] 系统调用异常 无回复"));
            }

            if ("assistant".equals(lastRole)
                    && lastMsg.getToolCalls() != null
                    && !lastMsg.getToolCalls().isEmpty()) {
                // assistant 带工具调用但缺少 tool 结果，补充错误 tool 消息使序列合法
                log.warn("AutoAgent【{}】补充缺失的 tool 结果消息", name);
                for (ToolCall tc : lastMsg.getToolCalls()) {
                    appendHistory(ChatMessage.withTool("工具调用因异常未完成", tc.getId()));
                }
                // tool 之后补充一条 assistant 终结消息
                appendHistory(ChatMessage.withAssistant("[工具调用因异常未完成]"));
            }

            // assistant 或 tool 结尾，序列合法
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
            boolean emptyContent = msg.getContent() == null
                    || StringUtils.isBlank(msg.getContent().getText());
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
     * 从JSON响应中提取content
     */
    private String extractContent(JSONObject json) {
        Object result = JSONPath.eval(json, "$.choices[0].delta.content");
        if (result == null) {
            result = JSONPath.eval(json, "$.choices[0].message.content");
        }
        return result != null ? result.toString() : null;
    }

    /**
     * 从JSON响应中提取tool_calls
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

    @Override
    public String toString() {
        return "AutoAgent{" + "name='" + name + '\'' + '}';
    }
}
