package net.itzq.mira.modules.ai.client.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.client.openai.chat.entity.ChatCompletionResponse;
import net.itzq.mira.modules.ai.client.openai.chat.entity.ChatMessage;
import net.itzq.mira.modules.ai.client.openai.chat.entity.Choice;
import net.itzq.mira.modules.ai.client.openai.tool.ToolCall;
import net.itzq.mira.modules.ai.client.openai.usage.Usage;
import net.itzq.mira.modules.ai.client.sse.SseException;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 *  OpenAICompatibleStreamEventHandler
 *
 *  @author tangzq
 */
@Slf4j
public class OpenAICompatibleStreamEventHandler extends StreamEventHandler {

    protected final SseEventListener listener;

    protected final CountDownLatch latch = new CountDownLatch(1);

    public OpenAICompatibleStreamEventHandler(SseEventListener listener) {
        this.listener = listener;
    }

    @Override
    public CountDownLatch getCountDownLatch() {
        return latch;
    }

    @Override
    public boolean needToolCall() {
        if ("tool_calls".equals(finishReason) && !toolCalls.isEmpty()) {
            return true;
        }
        return false;
    }

    @Override
    public String getCurrStr() {
        return currStr;
    }

    @Override
    public String getCurrData() {
        return currData;
    }

    @Override
    public StringBuilder getReasoningOutput() {
        return reasoningOutput;
    }

    @Override
    public StringBuilder getAnswerOutput() {
        return answerOutput;
    }

    @Override
    public void onEvent(String eventType, String data, String id) {
        try {
            this.onSseEvent(eventType, data, id);
            listener.onEvent(eventType, data, id, this);
        } catch (Exception e) {
            log.error("处理流式事件失败,中断请求", e);
            throw new RuntimeException(e);
        }

    }

    @Override
    public void onComment(String comment) {
        listener.onComment(comment, this);
    }

    @Override
    public void onComplete() {
        try {
            listener.onComplete(this);
        } finally {
            latch.countDown();
        }
    }

    @Override
    public void onError(Throwable t) {
        try {
            listener.onError(t, this);
        } finally {
            latch.countDown();
        }
    }

    /**
     * 流式输出，当前消息的内容(回答消息、函数参数)
     */
    protected String currStr = "";

    /**
     * 流式输出，当前单条SSE消息对象，即ChatCompletionResponse对象
     */
    protected String currData = "";

    @Getter
    protected ChatCompletionResponse currChatCompletionResponse = null;

    /**
     * 记录当前是否为思考状态reasoning
     */

    protected boolean isReasoning = false;

    @Override
    public boolean isReasoning() {
        return isReasoning;
    }

    protected boolean isChatAnswer = false;

    @Override
    public boolean isChatAnswer() {
        return isChatAnswer;
    }

    /**
     * 思考内容的输出
     */
    protected final StringBuilder reasoningOutput = new StringBuilder();
    /**
     * 最终的消息输出
     */
    protected final StringBuilder answerOutput = new StringBuilder();
    /**
     * 花费token
     */
    @Getter
    protected final Usage usage = new Usage();

    @Getter
    protected String finishReason = null;

    /**
     * 工具调用
     */
    @Setter
    @Getter
    protected List<ToolCall> toolCalls = new ArrayList<>();

    @Setter
    @Getter
    protected ToolCall toolCall;

    /**
     * 记录当前所调用函数工具的名称
     */
    @Getter
    protected String currToolName = "";

    /**
     * 最终的函数调用参数
     */
    protected final StringBuilder argument = new StringBuilder();

    /**
     * 是否显示每个函数调用输出的参数文本
     */
    @Getter
    @Setter
    protected boolean showToolArgs = false;

    public void onSseEvent(String eventType, String data, String id) {
        log.debug("onSseEvent {}", data);

        isChatAnswer = false;

        // 封装SSE消息对象
        currData = data;
        currStr = "";

        if ("[DONE]".equalsIgnoreCase(data)) {
            currChatCompletionResponse = null;
            return;
        }

        ObjectMapper objectMapper = new ObjectMapper();
        ChatCompletionResponse chatCompletionResponse = null;
        try {
            chatCompletionResponse = objectMapper.readValue(data, ChatCompletionResponse.class);
            this.currChatCompletionResponse = chatCompletionResponse;
        } catch (Exception e) {
            log.error("errer", e);
            throw new SseException("read data error");
        }

        // 统计token，当设置include_usage = true时，最后一条消息会携带usage, 其他消息中usage为null
        Usage currUsage = chatCompletionResponse.getUsage();
        if (currUsage != null) {
            usage.setPromptTokens(usage.getPromptTokens() + currUsage.getPromptTokens());
            usage.setCompletionTokens(usage.getCompletionTokens() + currUsage.getCompletionTokens());
            usage.setTotalTokens(usage.getTotalTokens() + currUsage.getTotalTokens());
        }

        List<Choice> choices = chatCompletionResponse.getChoices();
        if (choices == null || choices.isEmpty()) {
            return;
        }

        Choice choiceZero = choices.get(0);
        ChatMessage responseMessage = choiceZero.getDelta();
        finishReason = choiceZero.getFinishReason();

        // tool_calls回答已经结束
        if ("tool_calls".equals(finishReason)) {
            if (toolCall == null && responseMessage.getToolCalls() != null) {
                toolCalls = responseMessage.getToolCalls();
                if (showToolArgs) {
                    this.currStr = responseMessage.getToolCalls().get(0).getFunction().getArguments();
                }
                return;
            }

            if (toolCall != null) {
                toolCall.getFunction().setArguments(argument.toString());
                toolCalls.add(toolCall);
            }
            argument.setLength(0);
            currToolName = "";
            return;
        }

        // 消息回答完毕
        if ("stop".equals(finishReason)) {
            return;
        }

        // FC

        if (responseMessage.getToolCalls() == null) {

            if (toolCall != null && StringUtils.isNotEmpty(argument) && "assistant".equals(responseMessage.getRole())
                    && (responseMessage.getContent() != null && StringUtils.isNotEmpty(responseMessage.getContent()
                    .getText()))) {
                return;
            }

            // if (responseMessage.getContent() == null) {
            //     return;
            // }

            // 响应回答
            if (StringUtils.isNotEmpty(responseMessage.getReasoningContent())) {
                isReasoning = true;
                isChatAnswer = true;
                reasoningOutput.append(responseMessage.getReasoningContent());
                currStr = responseMessage.getReasoningContent();
            } else {
                isReasoning = false;
                if (responseMessage.getContent() != null) {
                    answerOutput.append(responseMessage.getContent().getText());
                    currStr = responseMessage.getContent().getText();
                    isChatAnswer = true;
                } else {
                    currStr = "";
                }
            }

        } else {

            // 函数调用回答
            commonToolCall(responseMessage);
        }

        // FCEND

    }

    protected void commonToolCall(ChatMessage responseMessage) {
        // 第一条ToolCall表示，不含参数信息
        if (StringUtils.isNotBlank(responseMessage.getToolCalls().get(0).getId())) {
            if (toolCall == null) {
                // 第一个函数
                toolCall = responseMessage.getToolCalls().get(0);
                if (toolCall != null) {
                    if (toolCall.getFunction().getArguments() != null) {
                        argument.append(toolCall.getFunction().getArguments());
                    }
                }
            } else {
                toolCall.getFunction().setArguments(argument.toString());
                argument.setLength(0);
                toolCalls.add(toolCall);
                toolCall = responseMessage.getToolCalls().get(0);
                if (toolCall != null) {
                    if (toolCall.getFunction().getArguments() != null) {
                        argument.append(toolCall.getFunction().getArguments());
                    }
                }
            }

            currToolName = responseMessage.getToolCalls().get(0).getFunction().getName();

        } else {

            String argStr = responseMessage.getToolCalls().get(0).getFunction().getArguments();
            if (argStr != null) {
                argument.append(argStr);
            }
            if (showToolArgs) {
                this.currStr = argStr;
            }
        }
    }

    private void qianfanToolCall(ChatMessage responseMessage) {
        //  完整ToolCall
        if (StringUtils.isNotBlank(responseMessage.getToolCalls().get(0).getId())) {
            toolCall = responseMessage.getToolCalls().get(0);
            if (toolCall != null) {
                if (toolCall.getFunction().getArguments() != null) {
                    argument.append(toolCall.getFunction().getArguments());
                }
                toolCalls.add(toolCall);
            }
            currToolName = responseMessage.getToolCalls().get(0).getFunction().getName();
        }
    }

}
