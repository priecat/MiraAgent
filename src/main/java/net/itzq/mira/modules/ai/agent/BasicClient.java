package net.itzq.mira.modules.ai.agent;

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
import net.itzq.mira.modules.ai.client.sse.SseException;
import net.itzq.mira.modules.ai.client.tool.FCUtil;
import net.itzq.mira.modules.ai.entity.chat.ReplyId;
import net.itzq.mira.modules.ai.persistence.AbstractHistoryPersist;
import net.itzq.mira.modules.ai.utils.ThreadPoolUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * BasicClient - 手动调用的单次 Client
 * 只发一次请求，不会自动循环调用工具。如果模型返回了 ToolCall，会直接返回给调用方，由调用方决定如何处理。
 *
 * @author tangzq
 */
@Slf4j
@Data
public class BasicClient   {

    private AgentContextHolder contextHolder;
    private String name;
    private String agentName;
    private String agentId;
    private String historyId;

    private int timeout = 60 * 10; // 10分钟

    private boolean streamChat = false;

    private AbstractHistoryPersist historyPersist = null;

    public BasicClient(AgentContextHolder contextHolder) {
        this(contextHolder, IdGen.uuidShort());
    }

    public BasicClient(AgentContextHolder contextHolder, String name) {
        if (contextHolder == null) {
            contextHolder = AgentContextHolder.builder().build();
        }

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

        this.agentId = uuidShort;
        this.agentName = this.name;

        if (contextHolder.getTopAgent() == null) {
            contextHolder.setTopAgent(null); // Client 不设置自身为 TopAgent，避免污染
        }
    }

    // ==================== 单次调用的结果包装 ====================

    /**
     * 单次调用的响应结果
     */
    @Data
    public static class ClientResponse {
        /** AI 的文本回复内容 */
        private String content;
        /** AI 请求调用的工具列表（如果为空说明没有工具调用） */
        private List<ToolCall> toolCalls;
        /** 原始的助手消息（已加入历史） */
        private ChatMessage assistantMessage;

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    // ==================== 同步单次入口 ====================

    /**
     * 同步单次调用 AI
     * @param question 用户问题
     * @return ClientResponse 包含回复内容或工具调用
     */
    public ClientResponse chat(String question) {
        if (StringUtils.isBlank(question)) {
            question = "";
        }
        streamChat = false;

        ChatMessage chatMessage = ChatMessage.withUser(question);
        contextHolder.addHistory(chatMessage);

        int snapshotLen = contextHolder.getHistory().size();

        try {
            // 调用 AI（仅调用一次，不循环）
            List<ToolCall> toolCalls = callAI();

            // 从历史中获取最后一次AI回复
            List<ChatMessage> history = contextHolder.getHistory();
            ChatMessage lastMessage = history.get(history.size() - 1);

            ClientResponse response = new ClientResponse();
            response.setToolCalls(toolCalls);
            response.setAssistantMessage(lastMessage);

            if (lastMessage != null && lastMessage.getContent() != null) {
                String text = lastMessage.getContent().getText();
                response.setContent(text);
                if (StringUtils.isBlank(text)) {
                    int index = contextHolder.getHistory().indexOf(lastMessage);
                    if (index >= 0) {
                       contextHolder.getHistory().remove(index);
                    }
                }
            }

            // 持久化
            persistHistory(snapshotLen);

            return response;
        } catch (Exception e) {
            log.error("BasicClient【{}】单次调用失败", name, e);
            throw new RuntimeException("AI 调用失败", e);
        }
    }

    // ==================== 流式单次入口 ====================

    /**
     * 流式单次调用 AI
     * @param question 用户问题
     * @return CountDownLatch 用于等待流式完成，完成后可通过 getClientResponse 获取结果
     */
    public CountDownLatch chatStream(String question) {
        return chatStream(question, true);
    }

    public CountDownLatch chatStream(String question, boolean user) {
        streamChat = true;
        String roundId = IdGen.uuid();

        int snapshotLen = contextHolder.getHistory().size();

        if (question != null) {
            if (StringUtils.isBlank(question)) {
                question = "";
            }
            ChatMessage chatMessage = ChatMessage.withUser(question);
            contextHolder.addHistory(chatMessage);
        }
        String finalQuestion = question != null ? question : "";

        CountDownLatch finalLatch = new CountDownLatch(1);
        // 用于保存流式完成后的结果
        AtomicReference<ClientResponse> responseRef = new AtomicReference<>();

        ExecutorService executorService = ThreadPoolUtil.getExecutorService();

        executorService.execute(new Runnable() {
            @Override
            public void run() {
                EventCenter eventCenter = contextHolder.getEventCenter();
                EventHook eventHook = contextHolder.getEventHook();

                try {
                    // 触发 Input 事件
                    {
                        ReplyId replyId = buildReplyId(roundId, 1);
                        ChatInputEvent event = new ChatInputEvent();
                        event.setQuestion(finalQuestion);
                        event.setReplyId(replyId);
                        event.setContext(contextHolder);
                        if (eventHook != null) eventHook.onChatInput(event);
                        if (user && eventCenter != null) eventCenter.fireChatInput(event);
                    }

                    // 触发 StepBegin 事件
                    {
                        ReplyId replyId = buildReplyId(roundId, 1);
                        StepBeginEvent event = new StepBeginEvent();
                        event.setName(name);
                        event.setDeep(1);
                        event.setReplyId(replyId);
                        event.setContext(contextHolder);
                        if (eventHook != null) eventHook.onStepBegin(event);
                        if (eventCenter != null) eventCenter.fireStepBegin(event);
                    }

                    // 执行单轮流式请求
                    CountDownLatch roundLatch = chatStreamRound(roundId);
                    roundLatch.await(timeout, TimeUnit.SECONDS);

                    // 触发 StepEnd 事件
                    {
                        ReplyId replyId = buildReplyId(roundId, 1);
                        StepEndEvent event = new StepEndEvent();
                        event.setName(name);
                        event.setDeep(1);
                        event.setReplyId(replyId);
                        event.setContext(contextHolder);
                        if (eventHook != null) eventHook.onStepEnd(event);
                        if (eventCenter != null) eventCenter.fireStepEnd(event);
                    }

                    // 触发 ChatEnd 事件
                    {
                        ReplyId replyId = buildReplyId(roundId, 1);
                        ChatEndEvent event = new ChatEndEvent();
                        event.setSuccess(true);
                        event.setException(null);
                        event.setReplyId(replyId);
                        event.setContext(contextHolder);
                        if (eventHook != null) eventHook.onChatEnd(event);
                        if (eventCenter != null) eventCenter.fireChatEnd(event);
                    }

                    // 持久化
                    persistHistory(snapshotLen);

                } catch (Exception e) {
                    log.error("BasicClient【{}】流式处理异常", name, e);
                    {
                        ReplyId replyId = buildReplyId(roundId, 1);
                        ChatEndEvent event = new ChatEndEvent();
                        event.setSuccess(false);
                        event.setException(e);
                        event.setReplyId(replyId);
                        event.setContext(contextHolder);
                        if (eventHook != null) eventHook.onChatEnd(event);
                        if (eventCenter != null) eventCenter.fireChatEnd(event);
                    }
                } finally {
                    finalLatch.countDown();
                }
            }
        });

        return finalLatch;
    }

    // ==================== 内部核心方法 ====================

    /**
     * 调用AI并返回工具调用列表（单次，无重试，保留原始异常）
     */
    private List<ToolCall> callAI() {
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
        } catch (SseException e) {
            log.error("BasicClient AI 调用失败 (SSE): statusCode={}, errMsg={}", e.getStatusCode(), e.getErrMsg());
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        JSONObject json = JSON.parseObject(response);

        String content = extractContent(json);
        List<ToolCall> toolCalls = extractToolCalls(json);

        ChatMessage assistantMessage;
        if (toolCalls.isEmpty()) {
            assistantMessage = ChatMessage.withAssistant(content);
        } else {
            assistantMessage = ChatMessage.withAssistant(toolCalls);
            if (StringUtils.isNotBlank(content)) {
                assistantMessage.setContent(Content.ofText(content));
            }
        }
        contextHolder.addHistory(assistantMessage);

        return toolCalls;
    }

    /**
     * 单轮流式调用
     */
    private CountDownLatch chatStreamRound(String roundId) {
        String msgId = IdGen.uuid();
        CountDownLatch roundLatch = new CountDownLatch(1);
        AtomicReference<Throwable> asyncError = new AtomicReference<>(null);

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
                try {
                    ReplyId replyId = buildReplyId(roundId, 1);
                    GeneralEvent event = new GeneralEvent();
                    event.setEventType(eventType);
                    event.setData(data);
                    event.setId(id);
                    event.setHandler(handler);
                    event.setReplyId(replyId);
                    event.setContext(contextHolder);

                    if (eventHook != null) eventHook.onEvent(event);
                    if (eventCenter != null) eventCenter.fireEvent(event);
                } catch (Exception e) {
                    log.error("BasicClient 流式 onEvent 处理出错: {}", e.getMessage(), e);
                    asyncError.compareAndSet(null, e);
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
                        log.info("BasicClient【{}】收到{}个工具调用，等待外部处理", name, toolCalls.size());

                        ChatMessage responseMessage = ChatMessage.withAssistant(toolCalls);
                        String streamedContent = handler.getAnswerOutput().toString();
                        if (streamedContent != null && !streamedContent.trim().isEmpty()) {
                            responseMessage.setContent(Content.ofText(streamedContent));
                        }
                        StringBuilder reasoningBuf = handler.getReasoningOutput();
                        if (reasoningBuf != null && reasoningBuf.length() > 0) {
                            String reasoningStr = reasoningBuf.toString().trim();
                            if (!reasoningStr.isEmpty()) {
                                responseMessage.setReasoningContent(reasoningStr);
                            }
                        }
                        contextHolder.addHistory(responseMessage);
                    } else {
                        log.info("BasicClient【{}】完成，无工具调用", name);
                        ChatMessage responseMessage = ChatMessage.withAssistant(handler.getAnswerOutput().toString());
                        StringBuilder reasoningBuf = handler.getReasoningOutput();
                        if (reasoningBuf != null && reasoningBuf.length() > 0) {
                            String reasoningStr = reasoningBuf.toString().trim();
                            if (!reasoningStr.isEmpty()) {
                                responseMessage.setReasoningContent(reasoningStr);
                            }
                        }
                        contextHolder.addHistory(responseMessage);
                    }
                } catch (Exception e) {
                    log.error("BasicClient onComplete 处理失败", name, e);
                    asyncError.compareAndSet(null, e);
                } finally {
                    long end = System.currentTimeMillis();
                    log.info("[BasicClient-{}] onComplete 耗时{}s", name, (end - begin) / 1000.0);
                    roundLatch.countDown();
                }
            }

            @Override
            public void onError(Throwable t, StreamEventHandler handler) {
                log.error("BasicClient 流式处理出错: {}", t.getMessage(), t);
                asyncError.compareAndSet(null, t);

                try {
                    ReplyId replyId = buildReplyId(roundId, 1);
                    ErrorEvent event = new ErrorEvent();
                    event.setThrowable(t);
                    event.setReplyId(replyId);
                    event.setContext(contextHolder);

                    if (eventHook != null) eventHook.onError(event);
                    if (eventCenter != null) eventCenter.fireError(event);
                } catch (Exception e) {
                    log.error("BasicClient onError 事件分发失败: {}", e.getMessage(), e);
                } finally {
                    roundLatch.countDown();
                }
            }
        };

        try {
            chatService.getResult(apiRequestParams, null, listener);
        } catch (SseException e) {
            log.error("BasicClient 流式请求失败 (SSE): statusCode={}, errMsg={}", e.getStatusCode(), e.getErrMsg());
            throw e;
        } catch (Exception e) {
            log.error("BasicClient 流式请求失败", e);
            throw new SseException(e);
        }

        // 包装 latch 以便抛出异步异常
        return new CountDownLatch(1) {
            @Override
            public void await() throws InterruptedException {
                roundLatch.await();
                propagateAsyncError();
            }

            @Override
            public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
                boolean completed = roundLatch.await(timeout, unit);
                if (completed) {
                    propagateAsyncError();
                } else {
                    Throwable error = asyncError.get();
                    if (error != null) {
                        throwAsRuntimeException(error);
                    }
                    asyncError.compareAndSet(null, new SseException(0, "TIMEOUT", "流式请求超时", ""));
                    propagateAsyncError();
                }
                return completed;
            }

            private void propagateAsyncError() {
                Throwable error = asyncError.get();
                if (error != null) {
                    throwAsRuntimeException(error);
                }
            }

            private void throwAsRuntimeException(Throwable error) {
                if (error instanceof SseException) throw (SseException) error;
                if (error instanceof RuntimeException) throw (RuntimeException) error;
                if (error instanceof Error) throw (Error) error;
                throw new RuntimeException(error);
            }
        };
    }

    // ==================== 工具方法 ====================

    private void persistHistory(int snapshotLen) {
        if (historyPersist != null) {
            List<ChatMessage> allMessages = contextHolder.getHistory();
            List<ChatMessage> newMessages = new ArrayList<>();
            for (int i = snapshotLen; i < allMessages.size(); i++) {
                newMessages.add(allMessages.get(i));
            }
            historyPersist.saveChatMessages(newMessages, contextHolder);
        }
    }

    private ReplyId buildReplyId(String roundId, int deep) {
        ReplyId replyId = new ReplyId(historyId, agentId, roundId, deep, IdGen.uuid(), agentName);
        if (contextHolder.getParentAgent() != null) {
            replyId.setParentAgentId(contextHolder.getParentAgent().getAgentId());
        }
        return replyId;
    }

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

    private String extractContent(JSONObject json) {
        Object result = JSONPath.eval(json, "$.choices[0].delta.content");
        if (result == null) {
            result = JSONPath.eval(json, "$.choices[0].message.content");
        }
        return result != null ? result.toString() : null;
    }

    @SuppressWarnings("unchecked")
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

    @Override
    public String toString() {
        return "BasicClient{" + "name='" + name + '\'' + '}';
    }
}
