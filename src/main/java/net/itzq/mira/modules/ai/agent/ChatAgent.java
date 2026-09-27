package net.itzq.mira.modules.ai.agent;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.core.utils.PromptLoader;
import net.itzq.mira.core.utils.PropsMap;
import net.itzq.mira.modules.toolfun.ToolFun;
import org.apache.commons.lang3.StringUtils;

/**
 * ChatAgent - 问答agent（只读：不挂工具，仅装配提示词）
 *
 * @created 2026/7/23 0:13
 * @deprecated 2026-09-25：改用 {@link AutoAgent}（构造纯净、装配显式、工具集可声明为只读）。
 *             本类保留仅为兼容既有调用；预期后续版本删除。
 */
@Deprecated
@Slf4j
public class ChatAgent extends BasicAgent {

    /** 系统提示词模板路径 */
    public static final String SYS_PROMPT_PATH = "assets/prompt/chat-agent.md";

    public ChatAgent(AgentContextHolder contextHolder) {
        super(contextHolder);
        initSkillsAndPrompt(contextHolder);
    }

    public ChatAgent(AgentContextHolder contextHolder, String name) {
        super(contextHolder, name);
        initSkillsAndPrompt(contextHolder);
    }

    /**
     * 初始化系统提示词
     */
    private void initSkillsAndPrompt(AgentContextHolder contextHolder) {
        String prompt = buildSystemPrompt(contextHolder.getPrompt());
        contextHolder.setPrompt(prompt);
    }

    /** @deprecated 改用 {@link AutoAgent#getDefaultVfsReadTools()} */
    @Deprecated
    public static String[] getDefaultVfsTools() {
        return AutoAgent.getDefaultVfsReadTools();
    }

    // ==================== sys 工具包基线（已迁 AutoAgent，此处仅兼容委托） ====================

    /** @deprecated 改用 {@link AutoAgent#systemBaselineCoreTools()} */
    @Deprecated
    public static java.util.List<String> systemBaselineCoreTools() {
        return AutoAgent.systemBaselineCoreTools();
    }

    /** @deprecated 改用 {@link AutoAgent#systemBaselineTools(java.util.Collection)} */
    @Deprecated
    public static java.util.List<String> systemBaselineTools(java.util.Collection<String> hostExtras) {
        return AutoAgent.systemBaselineTools(hostExtras);
    }

    /**
     * 构建系统提示词
     */
    public static String buildSystemPrompt(String extendsPrompt) {
        String template = PromptLoader.readFileString(SYS_PROMPT_PATH);
        if (StringUtils.isBlank(template)) {
            log.warn("系统提示词模板为空: {}", SYS_PROMPT_PATH);
            return "你是一个AI助手。";
        }

        PropsMap params = new PropsMap();

        if (StringUtils.isNotBlank(extendsPrompt)){
            params.put("extendsPrompt", extendsPrompt);
        }

        return PromptLoader.prompt(SYS_PROMPT_PATH,params);
    }
}
