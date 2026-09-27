package net.itzq.mira.modules.ai.agent;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.core.utils.PropsMap;
import net.itzq.mira.core.utils.PromptLoader;
import net.itzq.mira.modules.ai.mcp.McpPrepared;
import net.itzq.mira.modules.ai.mcp.McpToolInfo;
import net.itzq.mira.modules.ai.skills.SkillEntity;
import net.itzq.mira.modules.ai.skills.SkillManager;
import net.itzq.mira.modules.ai.skills.SkillRepository;
import net.itzq.mira.modules.config.GlobalConfigManager;
import net.itzq.mira.modules.toolfun.ToolFun;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AutoAgent —— 通用 Agent（2026-09-25 重写：吸收应用层 MiraAgent 的纯净装配设计）。
 *
 * <h3>设计原则：构造纯净，装配显式</h3>
 * 原实现（构造期隐式注入系统提示词 + 默认 code 工具 + 技能/MCP 工具）会与应用层
 * 白名单重复，被 LLM 网关以 {@code duplicate names} 拒绝，也无法表达"空工具 Agent"。
 * 重写后：
 * <ul>
 *   <li>构造器<b>不隐式注入任何提示词与工具</b>；</li>
 *   <li>技能/MCP/提示词动态段以静态方法提供，由调用方显式装配；</li>
 *   <li>工具以"工具集声明"形式提供（{@link #readOnlyTools()} / {@link #fullCodeTools()}），
 *       配合应用层白名单一次性挂载——<b>不挂写类工具即为只读 Agent</b>。</li>
 * </ul>
 *
 * @created 2026/7/23 0:13
 */
@Slf4j
public class AutoAgent extends BasicAgent {

    /** 系统提示词模板路径（旧接口 {@link #buildSystemPrompt} 使用；新装配走 PromptLoader + 应用层模板） */
    public static final String SYS_PROMPT_PATH = "assets/prompt/auto-agent.md";

    /**
     * 技能目录路径（懒读配置，避免类初始化期依赖配置装载顺序）。
     *
     * @deprecated P5 多实例：静态助手只能取到**默认运行时**的声明，多实例下取错技能目录。
     *             请改走实例：<code>contextHolder.getRuntime().declaration().getAgentConfig().getSkillsDir()</code>
     *             （技能仓储的懒加载已按此来源，见 {@link #resolveActiveSkills}）。
     */
    @Deprecated
    public static String skillsDir() {
        return GlobalConfigManager.config().getAgentConfig().getSkillsDir();
    }

    // ==================== 构造：纯净，无隐式注入 ====================

    public AutoAgent(AgentContextHolder contextHolder) {
        super(contextHolder);
    }

    public AutoAgent(AgentContextHolder contextHolder, String name) {
        super(contextHolder, name);
    }

    // ==================== 工具集声明（只读 / 全量） ====================

    /**
     * 只读工具集：检索与查看类（内核 code 只读 + VFS 只读）。
     * 不挂任何写类工具（Bash/Edit/Write/vfs_file_edit/vfs_file_write）即为只读 Agent。
     */
    public static List<String> readOnlyTools() {
        List<String> list = new ArrayList<>();
        list.add(ToolFun.TOOL_Read);
        list.add(ToolFun.TOOL_Glob);
        list.add(ToolFun.TOOL_Grep);
        list.add(ToolFun.TOOL_LIST_FILES);
        list.add(ToolFun.TOOL_CODE_EXPLORER);
        for (String t : getDefaultVfsReadTools()) {
            list.add(t);
        }
        return list;
    }

    /** 写类工具（只读判定与全量工具集的差集来源） */
    public static List<String> writeTools() {
        List<String> list = new ArrayList<>();
        list.add(ToolFun.TOOL_Bash);
        list.add(ToolFun.TOOL_Edit);
        list.add(ToolFun.TOOL_Write);
        list.add(ToolFun.Tool_VFS_File_Edit);
        list.add(ToolFun.Tool_VFS_File_Write);
        return list;
    }

    /** 全量 code 工具集：只读 ∪ 写类（含 VFS 只读；VFS 写类由调用方按需追加） */
    public static List<String> fullCodeTools() {
        LinkedHashSet<String> set = new LinkedHashSet<>(readOnlyTools());
        set.addAll(writeTools());
        return new ArrayList<>(set);
    }

    /**
     * sys 工具包基线（内核部分）：内核默认 code 工具集（含写类）+ VFS 只读工具。
     * 与旧 {@code ChatAgent.systemBaselineCoreTools()} 语义一致，迁至本类（ChatAgent 已废弃）。
     */
    public static List<String> systemBaselineCoreTools() {
        List<String> list = new ArrayList<>();
        list.add(ToolFun.TOOL_Bash);
        list.add(ToolFun.TOOL_Edit);
        list.add(ToolFun.TOOL_Read);
        list.add(ToolFun.TOOL_Write);
        list.add(ToolFun.TOOL_Glob);
        list.add(ToolFun.TOOL_Grep);
        list.add(ToolFun.TOOL_LIST_FILES);
        list.add(ToolFun.TOOL_CODE_EXPLORER);
        for (String t : getDefaultVfsReadTools()) {
            list.add(t);
        }
        return list;
    }

    /**
     * sys 工具包基线（完整）：内核部分 ∪ 宿主附加（如任务三件套 / recall_history / ask_user）。
     *
     * @param hostExtras 宿主附加工具名（可 null）
     */
    public static List<String> systemBaselineTools(Collection<String> hostExtras) {
        LinkedHashSet<String> set = new LinkedHashSet<>(systemBaselineCoreTools());
        if (hostExtras != null) {
            set.addAll(hostExtras);
        }
        return new ArrayList<>(set);
    }

    /** VFS 只读工具（VFS 写类不计入，保证只读语义） */
    public static String[] getDefaultVfsReadTools() {
        return new String[] {
                ToolFun.Tool_VFS_Glob,
                ToolFun.Tool_VFS_Grep,
                ToolFun.Tool_VFS_File_Read,
                ToolFun.Tool_VFS_List_Files,
                ToolFun.TOOL_VFS_Explorer };
    }

    /** 工具集是否只读（不含任何写类工具） */
    public static boolean isReadOnly(Collection<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return true;
        }
        for (String t : tools) {
            if (writeTools().contains(t)) {
                return false;
            }
        }
        return true;
    }

    // ==================== 显式装配便捷方法 ====================

    /** 挂载工具（已存在则跳过，幂等；返回自身便于链式） */
    public AutoAgent mountToolsIfAbsent(String... tools) {
        if (tools == null) {
            return this;
        }
        List<String> current = getContextHolder().getTools();
        for (String t : tools) {
            if (t != null && (current == null || !current.contains(t))) {
                getContextHolder().addTools(t);
            }
        }
        return this;
    }

    /** 挂载只读工具集（不挂写类工具 → 只读 Agent） */
    public AutoAgent mountReadOnlyTools() {
        return mountToolsIfAbsent(readOnlyTools().toArray(new String[0]));
    }

    /** 挂载全量 code 工具集（含写类） */
    public AutoAgent mountFullCodeTools() {
        return mountToolsIfAbsent(fullCodeTools().toArray(new String[0]));
    }

    // ==================== 技能 / MCP 装配（吸收自应用层 MiraAgent） ====================

    /**
     * 解析本次会话激活的技能（技能管理器未初始化时按全局配置懒加载；
     * activeSkillSlugs 为空或技能未装载时返回空列表）。
     */
    public static List<SkillEntity> resolveActiveSkills(AgentContextHolder contextHolder) {
        SkillRepository skillManager = contextHolder.getRuntime().skills();
        if (!skillManager.isInitialized()) {
            skillManager.init(contextHolder.getRuntime().declaration().getAgentConfig().getSkillsDir());
        }
        List<SkillEntity> activeSkills = new ArrayList<>();
        List<String> activeSlugs = contextHolder.getActiveSkillSlugs();
        if (activeSlugs != null) {
            for (String slug : activeSlugs) {
                SkillEntity skill = skillManager.getSkill(slug);
                if (skill != null) {
                    activeSkills.add(skill);
                }
            }
        }
        return activeSkills;
    }

    /**
     * 技能 / MCP 配套工具并入白名单（统一去重，由调用方一次性 addTools）：
     * 有激活技能 → use_skill / read_skill_file / expand_skill；有 MCP 工具 → mcp_call。
     */
    public static void mountSkillAndMcpTools(Set<String> whitelist,
                                             List<SkillEntity> activeSkills,
                                             McpPrepared mcpConfig) {
        if (whitelist == null) {
            return;
        }
        if (activeSkills != null && !activeSkills.isEmpty()) {
            whitelist.add(ToolFun.TOOL_USE_SKILL);
            whitelist.add(ToolFun.TOOL_READ_SKILL_FILE);
            whitelist.add(ToolFun.TOOL_EXPAND_SKILL);
        }
        if (mcpConfig != null && mcpConfig.getTools() != null && !mcpConfig.getTools().isEmpty()) {
            whitelist.add(ToolFun.TOOL_MCP_CALL);
        }
    }

    /**
     * 技能 / MCP 提示词参数（渲染进应用层系统提示词模板的动态段）：
     * 返回 skills / skillCount / skillSlugs / mcpTools / mcpToolCount 五个键。
     */
    public static PropsMap buildSkillMcpPromptParams(List<SkillEntity> activeSkills, McpPrepared mcpConfig) {
        PropsMap params = new PropsMap();

        StringBuilder skillsList = new StringBuilder();
        StringBuilder skillSlugs = new StringBuilder();
        if (activeSkills != null) {
            for (SkillEntity skill : activeSkills) {
                skillsList.append(skill.toSummary()).append("\n");
                if (skillSlugs.length() > 0) {
                    skillSlugs.append(", ");
                }
                skillSlugs.append(skill.getSlug());
            }
        }
        params.put("skills", skillsList.toString().trim());
        params.put("skillCount", String.valueOf(activeSkills == null ? 0 : activeSkills.size()));
        params.put("skillSlugs", skillSlugs.toString());

        List<McpToolInfo> mcpTools = mcpConfig != null ? mcpConfig.getTools() : null;
        StringBuilder mcpToolsList = new StringBuilder();
        if (mcpTools != null) {
            for (McpToolInfo tool : mcpTools) {
                mcpToolsList.append(tool.toSummary()).append("\n");
            }
        }
        params.put("mcpTools", mcpToolsList.toString().trim());
        params.put("mcpToolCount", String.valueOf(mcpTools == null ? 0 : mcpTools.size()));
        return params;
    }

    // ==================== 旧接口（保留兼容，建议改用上述显式装配） ====================

    /**
     * 旧：用内核自带模板 {@code assets/prompt/auto-agent.md} 渲染系统提示词。
     *
     * @deprecated 应用层已自持提示词模板（{@code core:prompt/agent_system.md}）；
     *             动态段请用 {@link #buildSkillMcpPromptParams}，模板渲染交 PromptLoader。
     */
    @Deprecated
    public static String buildSystemPrompt(List<SkillEntity> activeSkills, McpPrepared mcpConfig,
                                           String extendsPrompt) {
        String template = PromptLoader.readFileString(SYS_PROMPT_PATH);
        if (StringUtils.isBlank(template)) {
            log.warn("系统提示词模板为空: {}", SYS_PROMPT_PATH);
            return "你是一个AI助手。";
        }
        PropsMap params = buildSkillMcpPromptParams(activeSkills, mcpConfig);
        if (StringUtils.isNotBlank(extendsPrompt)) {
            params.put("extendsPrompt", extendsPrompt);
        }
        return PromptLoader.processTemplate(PromptLoader.createTemplate(template), params);
    }
}
