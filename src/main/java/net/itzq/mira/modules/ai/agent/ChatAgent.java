package net.itzq.mira.modules.ai.agent;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.core.utils.PromptLoader;
import net.itzq.mira.core.utils.PropsMap;
import net.itzq.mira.modules.toolfun.ToolFun;
import org.apache.commons.lang3.StringUtils;

/**
 * ChatAgent - 问答agent
 *
 * @created 2026/7/23 0:13
 */
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

    public static String[] getDefaultVfsTools() {
        return new String[] {
                ToolFun.Tool_VFS_Glob,
                ToolFun.Tool_VFS_Grep,
                ToolFun.Tool_VFS_File_Read,
                ToolFun.Tool_VFS_List_Files,
                ToolFun.TOOL_VFS_Explorer };
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
