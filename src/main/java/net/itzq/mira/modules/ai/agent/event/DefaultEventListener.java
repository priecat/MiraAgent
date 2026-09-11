package net.itzq.mira.modules.ai.agent.event;

import com.alibaba.fastjson2.JSONObject;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.agent.event.type.*;
import net.itzq.mira.modules.ai.client.handle.StreamEventHandler;
import net.itzq.mira.modules.ai.client.openai.tool.Tool;
import net.itzq.mira.modules.ai.client.openai.tool.ToolCall;
import net.itzq.mira.modules.ai.client.sse.IEmitter;
import net.itzq.mira.modules.ai.client.sse.SseException;
import net.itzq.mira.modules.ai.client.tool.FCUtil;
import net.itzq.mira.modules.ai.entity.chat.AnsResponse;
import net.itzq.mira.modules.ai.entity.chat.AnsType;
import net.itzq.mira.modules.ai.entity.chat.ReplyId;
import net.itzq.mira.modules.ai.persistence.AbstractHistoryPersist;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 默认事件监听器
 *
 * @author tangzq
 */
public abstract class DefaultEventListener implements EventListener, IEmitter {

    private final Queue<AnsResponse> ansResponseQueue = new ConcurrentLinkedQueue<>();

    public void sseEmitterCacheAndSend(AnsResponse response, AgentContextHolder context) throws Exception {
        AbstractHistoryPersist historyPersist = context.getTopAgent().getHistoryPersist();
        if (historyPersist != null) {
            ansResponseQueue.add(response);
        }
        sseEmitterSend(response);
    }

    // ======================  事件接口实现 ======================

    @Override
    public void onEvent(GeneralEvent event) {
        StreamEventHandler handler = event.getHandler();
        if (handler == null || !handler.isChatAnswer()) {
            return;
        }

        ReplyId replyId = event.getReplyId();
        AgentContextHolder context = event.getContext();
        String currStr = handler.getCurrStr();

        AnsResponse ans = new AnsResponse();
        ans.setHistoryId(replyId.getHistoryId());
        ans.setAgentId(replyId.getAgentId());
        ans.setAgentName(replyId.getAgentName());
        ans.setParentAgentId(replyId.getParentAgentId());
        ans.setRoundId(replyId.getRoundId());
        ans.setMsgId(replyId.getMsgId());
        ans.setAnswer(currStr);
        ans.setType(handler.isReasoning() ? AnsType.ChatReasonContent.toString() : AnsType.ChatContent.toString());

        try {
            sseEmitterCacheAndSend(ans, context);
        } catch (Exception e) {
            SseException err = new SseException(e.getMessage());
            err.setException(e);
            throw err;
        }
    }

    @Override
    public void onChatInput(ChatInputEvent event) {
        ReplyId replyId = event.getReplyId();
        AgentContextHolder context = event.getContext();
        String question = event.getQuestion();

        AnsResponse ans = new AnsResponse();
        ans.setHistoryId(replyId.getHistoryId());
        ans.setAgentId(replyId.getAgentId());
        ans.setAgentName(replyId.getAgentName());
        ans.setParentAgentId(replyId.getParentAgentId());
        ans.setRoundId(replyId.getRoundId());
        ans.setMsgId(replyId.getMsgId());

        JSONObject info = new JSONObject();
        info.put("question", question);
        ans.setInfo(info);
        ans.setType(AnsType.UserInput.toString());

        try {
            sseEmitterCacheAndSend(ans, context);
        } catch (Exception e) {
            SseException err = new SseException(e.getMessage());
            err.setException(e);
            throw err;
        }
        saveEvtCallback(context);
    }

    @Override
    public void onStepBegin(StepBeginEvent event) {
        ReplyId replyId = event.getReplyId();
        AgentContextHolder context = event.getContext();
        String name = event.getName();
        int deep = event.getDeep();

        AnsResponse ans = new AnsResponse();
        ans.setHistoryId(replyId.getHistoryId());
        ans.setAgentId(replyId.getAgentId());
        ans.setAgentName(replyId.getAgentName());
        ans.setParentAgentId(replyId.getParentAgentId());
        ans.setRoundId(replyId.getRoundId());
        ans.setMsgId(replyId.getMsgId());

        JSONObject info = new JSONObject();
        info.put("name", name);
        info.put("deep", deep);
        ans.setInfo(info);
        ans.setType(AnsType.StepBegin.toString());

        try {
            sseEmitterCacheAndSend(ans, context);
        } catch (Exception e) {
            SseException err = new SseException(e.getMessage());
            err.setException(e);
            throw err;
        }
        saveEvtCallback(context);
    }

    @Override
    public void onStepEnd(StepEndEvent event) {
        ReplyId replyId = event.getReplyId();
        AgentContextHolder context = event.getContext();
        String name = event.getName();
        int deep = event.getDeep();

        AnsResponse ans = new AnsResponse();
        ans.setHistoryId(replyId.getHistoryId());
        ans.setAgentId(replyId.getAgentId());
        ans.setAgentName(replyId.getAgentName());
        ans.setParentAgentId(replyId.getParentAgentId());
        ans.setRoundId(replyId.getRoundId());
        ans.setMsgId(replyId.getMsgId());

        JSONObject info = new JSONObject();
        info.put("name", name);
        info.put("deep", deep);
        ans.setInfo(info);
        ans.setType(AnsType.StepEnd.toString());

        try {
            sseEmitterCacheAndSend(ans, context);
        } catch (Exception e) {
            SseException err = new SseException(e.getMessage());
            err.setException(e);
            throw err;
        }
        saveEvtCallback(context);
    }

    @Override
    public void onChatEnd(ChatEndEvent event) {
        ReplyId replyId = event.getReplyId();
        AgentContextHolder context = event.getContext();

        AnsResponse ans = new AnsResponse();
        ans.setHistoryId(replyId.getHistoryId());
        ans.setAgentId(replyId.getAgentId());
        ans.setAgentName(replyId.getAgentName());
        ans.setParentAgentId(replyId.getParentAgentId());
        ans.setRoundId(replyId.getRoundId());
        ans.setMsgId(replyId.getMsgId());

        JSONObject info = new JSONObject();
        ans.setInfo(info);
        ans.setType(AnsType.ChatEnd.toString());

        try {
            sseEmitterCacheAndSend(ans, context);
        } catch (Exception e) {
            SseException err = new SseException(e.getMessage());
            err.setException(e);
            throw err;
        }
        saveEvtCallback(context);
    }

    @Override
    public void onCallToolBegin(CallToolBeginEvent event) {
        ReplyId replyId = event.getReplyId();
        AgentContextHolder context = event.getContext();
        ToolCall toolCall = event.getToolCall();
        String toolId = event.getToolId();

        AnsResponse ans = new AnsResponse();
        ans.setHistoryId(replyId.getHistoryId());
        ans.setAgentId(replyId.getAgentId());
        ans.setAgentName(replyId.getAgentName());
        ans.setParentAgentId(replyId.getParentAgentId());
        ans.setRoundId(replyId.getRoundId());
        ans.setMsgId(replyId.getMsgId());

        JSONObject info = new JSONObject();
        if (toolCall != null) {
            try {
                String functionName = toolCall.getFunction().getName();
                Tool tool = FCUtil.getTool(functionName);
                String display = tool.getFunction().getDisplay();
                info.put("toolName", display);
                info.put("funName", functionName);
            } catch (Exception e) {
                info.put("toolName", "工具");
                info.put("funName", "tool");
            }
        }
        info.put("toolId", toolId);
        ans.setInfo(info);
        ans.setType(AnsType.CallToolBegin.toString());

        try {
            sseEmitterCacheAndSend(ans, context);
        } catch (Exception e) {
            SseException err = new SseException(e.getMessage());
            err.setException(e);
            throw err;
        }
        saveEvtCallback(context);
    }

    @Override
    public void onCallToolEnd(CallToolEndEvent event) {
        ReplyId replyId = event.getReplyId();
        AgentContextHolder context = event.getContext();
        ToolCall toolCall = event.getToolCall();
        String toolId = event.getToolId();
        // endMsg 暂未使用，可根据需要处理

        AnsResponse ans = new AnsResponse();
        ans.setHistoryId(replyId.getHistoryId());
        ans.setAgentId(replyId.getAgentId());
        ans.setAgentName(replyId.getAgentName());
        ans.setParentAgentId(replyId.getParentAgentId());
        ans.setRoundId(replyId.getRoundId());
        ans.setMsgId(replyId.getMsgId());

        JSONObject info = new JSONObject();
        if (toolCall != null) {
            try {
                String functionName = toolCall.getFunction().getName();
                Tool tool = FCUtil.getTool(functionName);
                String display = tool.getFunction().getDisplay();
                info.put("toolName", display);
                info.put("funName", functionName);
            } catch (Exception e) {
                info.put("toolName", "工具");
                info.put("funName", "tool");
            }
        }
        info.put("toolId", toolId);
        ans.setInfo(info);
        ans.setType(AnsType.CallToolEnd.toString());

        try {
            sseEmitterCacheAndSend(ans, context);
        } catch (Exception e) {
            SseException err = new SseException(e.getMessage());
            err.setException(e);
            throw err;
        }
        saveEvtCallback(context);
    }

    public abstract void onError(ErrorEvent event);

    // ====================== 私有辅助方法 ======================

    private void saveEvtCallback(AgentContextHolder context) {
        AbstractHistoryPersist historyPersist = context.getTopAgent().getHistoryPersist();
        if (historyPersist != null) {
            List<AnsResponse> toSave = new ArrayList<>();
            AnsResponse resp;
            while ((resp = ansResponseQueue.poll()) != null) {
                toSave.add(resp);
            }
            historyPersist.saveEventMessages(toSave);
        }
    }
}
