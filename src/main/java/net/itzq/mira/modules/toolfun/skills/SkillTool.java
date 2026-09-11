package net.itzq.mira.modules.toolfun.skills;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.tool.annotation.Tool;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
import net.itzq.mira.modules.ai.skills.SkillBundle;
import net.itzq.mira.modules.ai.skills.SkillEntity;
import net.itzq.mira.modules.ai.skills.SkillManager;
import net.itzq.mira.modules.toolfun.ToolFun;

import java.io.UncheckedIOException;
import java.util.List;

/**
 * 技能工具，让AI可以通过function call调用技能
 * 注册为 @Tool 后，AI可根据用户意图选择合适的技能
 */
@Slf4j
public class SkillTool {

    @Tool(
            name = ToolFun.TOOL_USE_SKILL,
            display = "使用技能",
            description = "调用指定技能。当用户的任务匹配某个技能的描述或触发词时，调用此工具加载技能的完整指令到上下文。" +
                    "技能提供专业领域知识和操作流程，加载后按照技能指令执行任务。" +
                    "参数skill_name为技能的slug标识，可通过<available_skills>列表查看。"
    )
    public String useSkill(
            @ToolParam(description = "技能slug名称，例如frontend-design，可通过<available_skills>列表查看可用技能") String skill_name,
            AgentContextHolder context
    ) {
        // 校验技能是否在本次可用范围
        List<String> activeSlugs = context.getActiveSkillSlugs();
        if (activeSlugs == null || !activeSlugs.contains(skill_name)) {
            return "技能不在本次可用范围: " + skill_name;
        }

        SkillManager manager = SkillManager.getInstance();
        if (!manager.isInitialized()) {
            return "技能系统未初始化";
        }

        SkillEntity skill = manager.getSkill(skill_name);
        if (skill == null) {
            // 尝试模糊匹配（仅限activeSkillSlugs范围内）
            for (String slug : activeSlugs) {
                if (slug != null && slug.contains(skill_name)) {
                    skill = manager.getSkill(slug);
                    if (skill != null) break;
                }
            }
        }

        if (skill == null) {
            return "技能不存在: " + skill_name + "。可用技能: " + manager.buildSkillsSummary();
        }

        log.info("AI调用技能: {} ({})", skill.getSlug(), skill.getName());

        // 返回技能的完整内容供AI加载到上下文
        StringBuilder result = new StringBuilder();
        result.append("<skill_content slug=\"").append(skill.getSlug()).append("\">\n");
        result.append(skill.getContent());
        result.append("\n</skill_content>");
        result.append("\n\n如需读取技能内的脚本或文档，使用 read_skill_file 工具，");
        result.append("参数 skill_name=").append(skill.getSlug());
        result.append("，file_path 为技能包内相对路径（如 scripts/build.py）。");

        return result.toString();
    }

    @Tool(
            name = ToolFun.TOOL_READ_SKILL_FILE,
            display = "读取技能文件",
            description = "从技能包(zip)中读取指定文件。用于读取技能内的脚本、参考文档等资源文件。" +
                    "skill_name为技能slug，file_path为技能包内相对路径。"
    )
    public String readSkillFile(
            @ToolParam(description = "技能slug，如 pptx-generator, minimax-pdf") String skill_name,
            @ToolParam(description = "技能包内文件相对路径，如 scripts/build.py, references/api.md") String file_path,
            AgentContextHolder context
    ) {
        // 校验技能是否在本次可用范围
        List<String> activeSlugs = context.getActiveSkillSlugs();
        if (activeSlugs == null || !activeSlugs.contains(skill_name)) {
            return "技能不在本次可用范围: " + skill_name;
        }

        try {
            SkillBundle bundle = SkillBundle.get(skill_name);
            if (!bundle.exists(file_path)) {
                return "文件不存在: " + skill_name + "/" + file_path;
            }
            return bundle.readFile(file_path);
        } catch (UncheckedIOException e) {
            return "读取技能文件失败: " + e.getMessage();
        } catch (Exception e) {
            return "读取技能文件异常: " + e.getMessage();
        }
    }
}
