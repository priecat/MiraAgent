package net.itzq.mira.modules.toolfun.skills;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.tool.annotation.Tool;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
import net.itzq.mira.modules.ai.skills.SkillEntity;
import net.itzq.mira.modules.ai.skills.SkillManager;
import net.itzq.mira.modules.toolfun.ToolFun;

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
            @ToolParam(description = "技能slug名称，例如 pptx-generator, minimax-pdf, minimax-xlsx, minimax-docx") String skill_name,
            AgentContextHolder context
    ) {
        SkillManager manager = SkillManager.getInstance();
        if (!manager.isInitialized()) {
            return "技能系统未初始化";
        }

        SkillEntity skill = manager.getSkill(skill_name);
        if (skill == null) {
            // 尝试模糊匹配
            for (SkillEntity s : manager.getAllSkills()) {
                if (s.getSlug() != null && s.getSlug().contains(skill_name)) {
                    skill = s;
                    break;
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
        result.append("\n\n技能目录: ").append(skill.getSkillDir());

        return result.toString();
    }
}
