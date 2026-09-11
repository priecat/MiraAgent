package net.itzq.mira.modules.ai.agent;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.core.utils.PropsMap;
import net.itzq.mira.core.utils.PromptLoader;
import net.itzq.mira.modules.ai.mcp.McpPrepared;
import net.itzq.mira.modules.ai.mcp.McpToolInfo;
import net.itzq.mira.modules.ai.skills.SkillEntity;
import net.itzq.mira.modules.ai.skills.SkillManager;
import net.itzq.mira.modules.config.GlobalConfigManager;
import net.itzq.mira.modules.toolfun.ToolFun;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * AutoAgent - 自动加载技能、MCP和系统提示词的Agent
 *
 * @created 2026/7/23 0:13
 */
@Slf4j
public class AutoAgent extends BasicAgent {

    /** 系统提示词模板路径 */
    public static final String SYS_PROMPT_PATH = "assets/prompt/auto-agent.md";

    /**
     * 技能目录路径
     */
    public static final String SKILLS_DIR = GlobalConfigManager.config().getAgentConfig().getSkillsDir();

    public AutoAgent(AgentContextHolder contextHolder) {
        super(contextHolder);
        initSkillsAndPrompt(contextHolder);
    }

    public AutoAgent(AgentContextHolder contextHolder, String name) {
        super(contextHolder, name);
        initSkillsAndPrompt(contextHolder);
    }

    /**
     * 初始化技能系统、MCP并加载系统提示词
     * MCP配置从 contextHolder.getMcpConfig() 读取（调用方在构建context前通过McpManager.prepare预解析）
     */
    private void initSkillsAndPrompt(AgentContextHolder contextHolder) {
        // 1. 初始化技能管理器
        SkillManager skillManager = SkillManager.getInstance();
        if (!skillManager.isInitialized()) {
            skillManager.init(SKILLS_DIR);
        }

        // 2. 从context获取MCP预解析结果
        McpPrepared mcpConfig = contextHolder.getMcpConfig();

        // 3. 解析本次可用的技能列表
        List<String> activeSlugs = contextHolder.getActiveSkillSlugs();
        List<SkillEntity> activeSkills = new ArrayList<>();
        if (activeSlugs != null) {
            for (String slug : activeSlugs) {
                SkillEntity skill = skillManager.getSkill(slug);
                if (skill != null) {
                    activeSkills.add(skill);
                }
            }
        }

        // 4. 增强系统提示词
        String prompt = buildSystemPrompt(activeSkills, mcpConfig, contextHolder.getPrompt());
        contextHolder.setPrompt(prompt);

        // 5. 默认工具
        contextHolder.addTools(
                ToolFun.TOOL_Bash,
                ToolFun.TOOL_Edit,
                ToolFun.TOOL_Read,
                ToolFun.TOOL_Write,
                ToolFun.TOOL_Glob,
                ToolFun.TOOL_Grep,
                ToolFun.TOOL_LIST_FILES,
                ToolFun.TOOL_CODE_EXPLORER
        );

        // 仅当有可用技能时注入技能工具
        if (!activeSkills.isEmpty()) {
            if (!contextHolder.getTools().contains(ToolFun.TOOL_USE_SKILL)) {
                contextHolder.addTools(ToolFun.TOOL_USE_SKILL);
            }
            if (!contextHolder.getTools().contains(ToolFun.TOOL_READ_SKILL_FILE)) {
                contextHolder.addTools(ToolFun.TOOL_READ_SKILL_FILE);
            }
        }
        if (mcpConfig != null && !mcpConfig.getTools().isEmpty()
                && !contextHolder.getTools().contains(ToolFun.TOOL_MCP_CALL)) {
            contextHolder.addTools(ToolFun.TOOL_MCP_CALL);
        }
    }

    /**
     * 构建系统提示词
     */
    public static String buildSystemPrompt(List<SkillEntity> activeSkills, McpPrepared mcpConfig, String extendsPrompt) {
        String template = PromptLoader.readFileString(SYS_PROMPT_PATH);
        if (StringUtils.isBlank(template)) {
            log.warn("系统提示词模板为空: {}", SYS_PROMPT_PATH);
            return "你是一个AI助手。";
        }

        PropsMap params = new PropsMap();

        // === 技能列表（仅注入activeSkills） ===
        StringBuilder skillsList = new StringBuilder();
        for (SkillEntity skill : activeSkills) {
            skillsList.append(skill.toSummary()).append("\n");
        }
        params.put("skills", skillsList.toString().trim());
        params.put("skillCount", String.valueOf(activeSkills.size()));

        StringBuilder skillSlugs = new StringBuilder();
        for (SkillEntity skill : activeSkills) {
            skillSlugs.append(skill.getSlug()).append(", ");
        }
        if (skillSlugs.length() > 2) {
            skillSlugs.setLength(skillSlugs.length() - 2);
        }
        params.put("skillSlugs", skillSlugs.toString());

        // === MCP工具列表 ===
        List<McpToolInfo> mcpTools = mcpConfig != null ? mcpConfig.getTools() : java.util.Collections.emptyList();
        StringBuilder mcpToolsList = new StringBuilder();
        for (McpToolInfo tool : mcpTools) {
            mcpToolsList.append(tool.toSummary()).append("\n");
        }
        params.put("mcpTools", mcpToolsList.toString().trim());
        params.put("mcpToolCount", String.valueOf(mcpTools.size()));
        if (StringUtils.isNotBlank(extendsPrompt)) {
            params.put("extendsPrompt", extendsPrompt);
        }

        return PromptLoader.processTemplate(
                PromptLoader.createTemplate(template),
                params
        );
    }
}
