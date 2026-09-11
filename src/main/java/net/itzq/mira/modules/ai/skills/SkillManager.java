package net.itzq.mira.modules.ai.skills;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能管理器，单例模式，管理技能注册表
 */
@Slf4j
public class SkillManager  {

    private static final SkillManager INSTANCE = new SkillManager();

    /** 技能注册表 slug -> SkillEntity */
    private final Map<String, SkillEntity> skillRegistry = new ConcurrentHashMap<>();

    /** 触发词索引 trigger -> List<slug> */
    private final Map<String, List<String>> triggerIndex = new ConcurrentHashMap<>();

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    private SkillManager() {
    }

    public static SkillManager getInstance() {
        return INSTANCE;
    }

    /**
     * 初始化，加载默认目录下的所有技能
     */
    public synchronized void init(String skillsDirPath) {
        if (initialized) {
            return;
        }
        List<SkillEntity> skills = SkillLoader.loadSkills(skillsDirPath);
        for (SkillEntity skill : skills) {
            registerSkill(skill);
        }
        initialized = true;
        log.info("SkillManager 初始化完成，共注册 {} 个技能", skillRegistry.size());
    }

    /**
     * 注册单个技能
     */
    public void registerSkill(SkillEntity skill) {
        if (skill == null || StringUtils.isBlank(skill.getSlug())) {
            return;
        }
        skillRegistry.put(skill.getSlug(), skill);
        // 建立触发词索引
        if (skill.getTriggers() != null) {
            for (String trigger : skill.getTriggers()) {
                triggerIndex.computeIfAbsent(trigger.toLowerCase(), k -> new ArrayList<>()).add(skill.getSlug());
            }
        }
    }

    /**
     * 按slug获取技能
     */
    public SkillEntity getSkill(String slug) {
        return skillRegistry.get(slug);
    }

    /**
     * 获取所有已注册技能
     */
    public Collection<SkillEntity> getAllSkills() {
        return skillRegistry.values();
    }

    /**
     * 根据用户输入匹配技能（基于触发词）
     */
    public List<SkillEntity> matchSkills(String userInput) {
        if (StringUtils.isBlank(userInput)) {
            return Collections.emptyList();
        }
        String lowerInput = userInput.toLowerCase();
        Set<SkillEntity> matched = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : triggerIndex.entrySet()) {
            if (lowerInput.contains(entry.getKey().toLowerCase())) {
                for (String slug : entry.getValue()) {
                    SkillEntity skill = skillRegistry.get(slug);
                    if (skill != null) {
                        matched.add(skill);
                    }
                }
            }
        }
        return new ArrayList<>(matched);
    }

    /**
     * 生成所有技能的摘要列表（用于系统提示词）
     */
    public String buildSkillsSummary() {
        if (skillRegistry.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SkillEntity skill : skillRegistry.values()) {
            sb.append(skill.toSummary()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 获取技能的完整内容
     */
    public String getSkillContent(String slug) {
        SkillEntity skill = skillRegistry.get(slug);
        if (skill == null) {
            return "技能不存在: " + slug;
        }
        return skill.getContent();
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }
}
