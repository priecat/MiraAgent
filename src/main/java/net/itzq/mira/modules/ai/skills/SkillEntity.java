package net.itzq.mira.modules.ai.skills;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能实体类，解析自SKILL.md的YAML frontmatter和正文内容
 */
@Data
public class SkillEntity   {
    /** 技能slug，唯一标识 */
    private String slug;
    /** 技能显示名称 */
    private String name;
    /** 技能描述 */
    private String description;
    /** 中文描述 */
    private String descriptionZh;
    /** 英文描述 */
    private String descriptionEn;
    /** 版本号 */
    private String version;
    /** 许可证 */
    private String license;
    /** 分类 */
    private String category;
    /** 触发词列表 */
    private List<String> triggers = new ArrayList<>();
    /** SKILL.md正文内容（frontmatter之后的部分） */
    private String content;
    /** 技能slug（用于SkillBundle加载zip包） */
    private String skillDir;
    /** 元数据来源 */
    private String source;

    /**
     * 生成用于系统提示词的技能摘要
     */
    public String toSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("- **").append(slug).append("**");
        if (name != null && !name.isEmpty()) {
            sb.append(" (").append(name).append(")");
        }
        sb.append(": ");
        String desc = descriptionZh != null && !descriptionZh.isEmpty() ? descriptionZh : description;
        if (desc != null) {
            // 截取第一行作为摘要
            String firstLine = desc.split("\n")[0].trim();
            if (firstLine.length() > 120) {
                firstLine = firstLine.substring(0, 117) + "...";
            }
            sb.append(firstLine);
        }
        return sb.toString();
    }
}
