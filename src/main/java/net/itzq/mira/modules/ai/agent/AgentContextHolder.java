package net.itzq.mira.modules.ai.agent;

import com.fasterxml.jackson.databind.JavaType;
import lombok.Builder;
import lombok.Data;
import net.itzq.mira.core.utils.IdGen;
import net.itzq.mira.core.utils.JsonMapper;
import net.itzq.mira.modules.ai.agent.event.EventCenter;
import net.itzq.mira.modules.ai.agent.event.EventHook;
import net.itzq.mira.modules.ai.client.handle.ApiRequestParams;
import net.itzq.mira.modules.ai.client.openai.chat.entity.ChatMessage;
import net.itzq.mira.modules.ai.client.sse.IEmitter;
import net.itzq.mira.modules.ai.mcp.McpPrepared;

import java.util.*;

/**
 *  AgentContextHolder
 *
 *  @author tangzq
 */
@Builder
@Data
public class AgentContextHolder {
    @Builder.Default
    ApiRequestParams requestParams = new ApiRequestParams();

    @Builder.Default
    String userId = ""; // 用户ID

    @Builder.Default
    String historyId = "";// 历史ID

    @Builder.Default
    String prompt = ""; // 系统提示词

    @Builder.Default
    String modelAlias = ""; // 模型标识 选择api用

    @Builder.Default
    List<String> tools = new ArrayList<>();  // 可用工具名称

    @Builder.Default
    List<ChatMessage> history = new ArrayList<>();

//    @Builder.Default
//    ActionBlackboard actionBlackboard = new ActionBlackboard();

    @Builder.Default
    Map<String, String> globalVariables = new LinkedHashMap<>();

    @Builder.Default
    Map<String, Object> tempVariables = new LinkedHashMap<>();

    @Builder.Default
    int currentDeep = 1;

    @Builder.Default
    String currentChatLoopRoundId = IdGen.uuid();

    BasicAgent topAgent;

    BasicAgent parentAgent;

    IEmitter emitter;

    EventCenter eventCenter;

    EventHook eventHook;

    String workspaceId;

    String vfsId;

    @Builder.Default
    McpPrepared mcpConfig = McpPrepared.empty();  // MCP配置（含configJson和工具列表）

    @Builder.Default
    List<String> activeSkillSlugs = new ArrayList<>();  // 本次对话可用的技能slug列表

    public void addHistory(ChatMessage message) {
        getHistory().add(message);
    }

    public List<ChatMessage> copyHistory() {
        List<ChatMessage> history = getHistory();

        String json = JsonMapper.toJsonString(history);
        JavaType collectionType = JsonMapper.getInstance().createCollectionType(ArrayList.class, ChatMessage.class);
        List<ChatMessage> copy = JsonMapper.getInstance().fromJson(json, collectionType);

        return copy;
    }

    public AgentContextHolder addTools(String... tools) {
        if (tools != null) {
            this.tools.addAll(Arrays.asList(tools));
        }
        return this;
    }

    public AgentContextHolder addHistory(ChatMessage... messages) {
        if (messages != null) {
            this.history.addAll(Arrays.asList(messages));
        }
        return this;
    }

    public EventCenter getEventCenter() {
        return eventCenter;
    }


    public EventCenter getTopEventCenter() {
        if (getTopAgent() != null) {
            return getTopAgent().getContextHolder().getEventCenter();
        }
        return eventCenter;
    }


    public Map<String, String> getTopGlobalVariables() {
        if (getTopAgent() != null) {
            return getTopAgent().getContextHolder().getGlobalVariables();
        }
        return globalVariables;
    }

    public Map<String, Object> getTopTempVariables() {
        if (getTopAgent() != null) {
            return getTopAgent().getContextHolder().getTempVariables();
        }
        return tempVariables;
    }


    /**
     * 获取顶层 Agent 的 Workspace
     */
    public String getTopWorkspaceId() {
        if (getTopAgent() != null) {
            return getTopAgent().getContextHolder().getWorkspaceId();
        }
        return getWorkspaceId();
    }

    public String getTopVfsId() {
        if (getTopAgent() != null) {
            return getTopAgent().getContextHolder().getVfsId();
        }
        return getVfsId();
    }
}
