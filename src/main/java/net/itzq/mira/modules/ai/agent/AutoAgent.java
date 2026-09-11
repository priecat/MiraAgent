package net.itzq.mira.modules.ai.agent;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.core.utils.PropsMap;
import net.itzq.mira.core.utils.PromptLoader;
import net.itzq.mira.modules.ai.mcp.McpManager;
import net.itzq.mira.modules.toolfun.ToolFun;
import net.itzq.mira.modules.toolfun.mcp.McpTool;
import net.itzq.mira.modules.ai.mcp.McpToolInfo;
import net.itzq.mira.modules.ai.skills.SkillEntity;
import net.itzq.mira.modules.ai.skills.SkillManager;
import net.itzq.mira.modules.toolfun.skills.SkillTool;
import org.apache.commons.lang3.StringUtils;

import java.util.Collection;

/**
 * AutoAgent - 自动加载技能、MCP和系统提示词的Agent
 *
 * @created 2026/7/23 0:13
 */
@Slf4j
public class AutoAgent extends BasicAgent {

    /** 系统提示词模板路径 */
    public static final String SYS_PROMPT_PATH = "assets/prompt/sys.md";

    /** 技能目录路径 */
    public static final String SKILLS_DIR = "data-skills/skills";

    public AutoAgent(AgentContextHolder contextHolder) {
        super(contextHolder);
        initSkillsAndPrompt(contextHolder, null);
    }

    public AutoAgent(AgentContextHolder contextHolder, String name) {
        super(contextHolder, name);
        initSkillsAndPrompt(contextHolder, null);
    }

    /**
     * 带MCP配置的构造函数
     *
     * @param contextHolder Agent上下文
     * @param name          Agent名称
     * @param mcpConfigJson MCP配置JSON字符串，格式: {"mcpServers": {...}}
     */
    public AutoAgent(AgentContextHolder contextHolder, String name, String mcpConfigJson) {
        super(contextHolder, name);
        initSkillsAndPrompt(contextHolder, mcpConfigJson);
    }

    /**
     * 初始化技能系统、MCP并加载系统提示词
     */
    private void initSkillsAndPrompt(AgentContextHolder contextHolder, String mcpConfigJson) {
        // 1. 初始化技能管理器
        SkillManager skillManager = SkillManager.getInstance();
        if (!skillManager.isInitialized()) {
            skillManager.init(SKILLS_DIR);
        }

        // 2. 注册SkillTool
        registerSkillTool();

        // 3. 初始化MCP管理器（如果提供了配置）
        McpManager mcpManager = McpManager.getInstance();
        if (!mcpManager.isInitialized()) {
            mcpManager.init(mcpConfigJson);
        }
        if (mcpManager.hasTools()) {
            registerMcpTool();
        }

        // 4. 增强系统提示词
        String prompt = buildSystemPrompt(skillManager, mcpManager, contextHolder.getPrompt());
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

        if (!contextHolder.getTools().contains("use_skill")) {
            contextHolder.addTools("use_skill");
        }
        if (mcpManager.hasTools() && !contextHolder.getTools().contains("mcp_call")) {
            contextHolder.addTools("mcp_call");
        }


    }

    /**
     * 注册SkillTool到FCUtil
     */
    private void registerSkillTool() {
        try {
            net.itzq.mira.modules.ai.client.tool.FCUtil.scanTools(SkillTool.class);
            log.info("SkillTool 已注册");
        } catch (Exception e) {
            log.error("注册SkillTool失败", e);
        }
    }

    /**
     * 注册McpTool到FCUtil
     */
    private void registerMcpTool() {
        try {
            net.itzq.mira.modules.ai.client.tool.FCUtil.scanTools(McpTool.class);
            log.info("McpTool 已注册");
        } catch (Exception e) {
            log.error("注册McpTool失败", e);
        }
    }

    /**
     * 构建系统提示词
     */
    public static String buildSystemPrompt(SkillManager skillManager, McpManager mcpManager,String extendsPrompt) {
        String template = PromptLoader.readFileString(SYS_PROMPT_PATH);
        if (StringUtils.isBlank(template)) {
            log.warn("系统提示词模板为空: {}", SYS_PROMPT_PATH);
            return "你是一个AI助手。";
        }

        PropsMap params = new PropsMap();

        // === 技能列表 ===
        Collection<SkillEntity> skills = skillManager.getAllSkills();
        StringBuilder skillsList = new StringBuilder();
        for (SkillEntity skill : skills) {
            skillsList.append(skill.toSummary()).append("\n");
        }
        params.put("skills", skillsList.toString().trim());
        params.put("skillCount", String.valueOf(skills.size()));

        StringBuilder skillSlugs = new StringBuilder();
        for (SkillEntity skill : skills) {
            skillSlugs.append(skill.getSlug()).append(", ");
        }
        if (skillSlugs.length() > 2) {
            skillSlugs.setLength(skillSlugs.length() - 2);
        }
        params.put("skillSlugs", skillSlugs.toString());

        // === MCP工具列表 ===
        Collection<McpToolInfo> mcpTools = mcpManager.getAllTools();
        StringBuilder mcpToolsList = new StringBuilder();
        for (McpToolInfo tool : mcpTools) {
            mcpToolsList.append(tool.toSummary()).append("\n");
        }
        params.put("mcpTools", mcpToolsList.toString().trim());
        params.put("mcpToolCount", String.valueOf(mcpTools.size()));
        if (StringUtils.isNotBlank(extendsPrompt)){
            params.put("extendsPrompt", extendsPrompt);
        }


        return PromptLoader.processTemplate(
                PromptLoader.createTemplate(template),
                params
        );
    }
}
