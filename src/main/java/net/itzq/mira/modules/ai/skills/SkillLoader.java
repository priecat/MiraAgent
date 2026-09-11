package net.itzq.mira.modules.ai.skills;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 技能加载器，扫描技能目录并解析SKILL.md和_skillhub_meta.json
 */
@Slf4j
public class SkillLoader  {

    /** 默认技能目录 */
    public static final String DEFAULT_SKILLS_DIR = "data-skills/skills";

    /**
     * 从指定目录加载所有技能
     */
    public static List<SkillEntity> loadSkills(String skillsDirPath) {
        List<SkillEntity> skills = new ArrayList<>();
        if (StringUtils.isBlank(skillsDirPath)) {
            skillsDirPath = DEFAULT_SKILLS_DIR;
        }

        File skillsDir = new File(skillsDirPath);
        if (!skillsDir.exists() || !skillsDir.isDirectory()) {
            log.warn("技能目录不存在: {}", skillsDirPath);
            return skills;
        }

        File[] skillDirs = skillsDir.listFiles(File::isDirectory);
        if (skillDirs == null) {
            return skills;
        }

        for (File skillDir : skillDirs) {
            try {
                SkillEntity skill = loadSkill(skillDir);
                if (skill != null) {
                    skills.add(skill);
                    log.info("已加载技能: {} ({})", skill.getSlug(), skill.getName());
                }
            } catch (Exception e) {
                log.error("加载技能失败: {}", skillDir.getName(), e);
            }
        }

        log.info("共加载 {} 个技能", skills.size());
        return skills;
    }

    /**
     * 加载单个技能目录
     */
    public static SkillEntity loadSkill(File skillDir) {
        // 1. 解析 _skillhub_meta.json
        File metaFile = new File(skillDir, "_skillhub_meta.json");
        JSONObject meta = null;
        if (metaFile.exists()) {
            try {
                String metaContent = new String(Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8);
                meta = JSONObject.parseObject(metaContent);
            } catch (Exception e) {
                log.warn("解析技能元数据失败: {}", metaFile.getPath(), e);
            }
        }

        // 2. 解析 SKILL.md
        File skillMdFile = new File(skillDir, "SKILL.md");
        if (!skillMdFile.exists()) {
            log.warn("SKILL.md 不存在: {}", skillMdFile.getPath());
            return null;
        }

        String skillMdContent;
        try {
            skillMdContent = new String(Files.readAllBytes(skillMdFile.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("读取SKILL.md失败: {}", skillMdFile.getPath(), e);
            return null;
        }

        // 3. 解析YAML frontmatter
        SkillEntity skill = parseFrontmatter(skillMdContent);
        if (skill == null) {
            return null;
        }

        // 4. 用meta.json补充信息
        if (meta != null) {
            if (StringUtils.isBlank(skill.getSlug())) {
                skill.setSlug(meta.getString("slug"));
            }
            if (StringUtils.isBlank(skill.getName())) {
                skill.setName(meta.getString("name"));
            }
            if (StringUtils.isBlank(skill.getVersion())) {
                skill.setVersion(meta.getString("version"));
            }
            skill.setSource(meta.getString("source"));
        }

        // 5. 设置目录路径
        skill.setSkillDir(skillDir.getAbsolutePath());

        return skill;
    }

    /**
     * 解析SKILL.md的YAML frontmatter
     */
    private static SkillEntity parseFrontmatter(String content) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }

        String trimmed = content.trim();
        if (!trimmed.startsWith("---")) {
            // 没有frontmatter，整个内容作为skill content
            SkillEntity skill = new SkillEntity();
            skill.setContent(content);
            return skill;
        }

        // 找到第二个 ---
        int secondDash = trimmed.indexOf("\n---", 3);
        if (secondDash == -1) {
            return null;
        }

        String frontmatter = trimmed.substring(3, secondDash).trim();
        String body = trimmed.substring(secondDash + 4).trim();

        SkillEntity skill = new SkillEntity();
        skill.setContent(body);

        // 逐行解析YAML frontmatter（简化版，不引入snakeyaml）
        String[] lines = frontmatter.split("\n");
        String currentKey = null;
        StringBuilder descBuilder = null;
        boolean inTriggers = false;
        boolean inDescription = false;

        for (String line : lines) {
            String trimmedLine = line.trim();

            // 处理多行描述结束
            if (inDescription && !line.startsWith(" ") && !line.startsWith("\t") && !trimmedLine.isEmpty()) {
                if (descBuilder != null) {
                    skill.setDescription(descBuilder.toString().trim());
                    descBuilder = null;
                }
                inDescription = false;
            }

            // 处理triggers列表项
            if (inTriggers) {
                if (trimmedLine.startsWith("- ")) {
                    String trigger = trimmedLine.substring(2).trim().replace("\"", "");
                    skill.getTriggers().add(trigger);
                    continue;
                } else if (!trimmedLine.isEmpty() && !trimmedLine.startsWith("-")) {
                    inTriggers = false;
                }
            }

            if (trimmedLine.startsWith("name:")) {
                skill.setSlug(parseYamlValue(trimmedLine));
            } else if (trimmedLine.startsWith("description_zh:")) {
                skill.setDescriptionZh(parseYamlValue(trimmedLine));
            } else if (trimmedLine.startsWith("description_en:")) {
                skill.setDescriptionEn(parseYamlValue(trimmedLine));
            } else if (trimmedLine.startsWith("version:")) {
                skill.setVersion(parseYamlValue(trimmedLine));
            } else if (trimmedLine.startsWith("license:")) {
                skill.setLicense(parseYamlValue(trimmedLine));
            } else if (trimmedLine.startsWith("category:")) {
                skill.setCategory(parseYamlValue(trimmedLine));
            } else if (trimmedLine.startsWith("description:")) {
                String val = trimmedLine.substring("description:".length()).trim();
                if (val.equals(">") || val.equals("|")) {
                    // 多行描述
                    inDescription = true;
                    descBuilder = new StringBuilder();
                } else {
                    skill.setDescription(parseQuotedValue(val));
                }
            } else if (trimmedLine.startsWith("triggers:")) {
                inTriggers = true;
            } else if (inDescription && (line.startsWith(" ") || line.startsWith("\t"))) {
                if (descBuilder != null) {
                    descBuilder.append(trimmedLine).append("\n");
                }
            }
        }

        // 处理末尾的多行描述
        if (descBuilder != null) {
            skill.setDescription(descBuilder.toString().trim());
        }

        return skill;
    }

    /**
     * 解析YAML单行值 key: value 或 key: "value"
     */
    private static String parseYamlValue(String line) {
        int colonIdx = line.indexOf(':');
        if (colonIdx == -1) {
            return "";
        }
        String value = line.substring(colonIdx + 1).trim();
        return parseQuotedValue(value);
    }

    /**
     * 去除引号包裹
     */
    private static String parseQuotedValue(String value) {
        if (value == null) {
            return "";
        }
        value = value.trim();
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        if (value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
