package net.itzq.mira.modules.ai.skills;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能仓储（编排运行时协议 · 实例组件）。
 *
 * <p>P5 实例化：原 {@link SkillManager} 的注册表/触发词索引/初始化状态平移到本类，
 * 每个内核运行时（KernelRuntime）拥有独立技能清单（可指向不同 skillsDir）；
 * {@link SkillManager} 降级为委托默认运行时的静态 facade。
 *
 * <p>技能 zip 包缓存（{@code SkillBundle}）已目录感知（key = 来源目录 + 技能名），
 * 多实例不同 skillsDir 的同名技能互不串扰；{@link #shutdown} 会释放本运行时打开的句柄。
 */
@Slf4j
public class SkillRepository {

    /** 技能注册表 slug -> SkillEntity */
    private final Map<String, SkillEntity> skillRegistry = new ConcurrentHashMap<>();

    /** 触发词索引 trigger -> List<slug> */
    private final Map<String, List<String>> triggerIndex = new ConcurrentHashMap<>();

    /** 本仓储的技能目录 */
    private volatile String skillsDir;

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    public SkillRepository() {
    }

    /** 初始化，加载指定目录下的所有技能（幂等） */
    public synchronized void init(String skillsDirPath) {
        if (initialized) {
            return;
        }
        this.skillsDir = skillsDirPath;
        // 兼容 facade：单参 SkillBundle.get/invalidate 的旧调用方仍走全局目录（单实例语义不变）
        SkillBundle.setSkillDir(skillsDirPath);
        List<SkillEntity> skills = SkillLoader.loadSkills(skillsDirPath);
        for (SkillEntity skill : skills) {
            registerSkill(skill);
        }
        initialized = true;
        log.info("SkillRepository 初始化完成，共注册 {} 个技能", skillRegistry.size());
    }

    /** 关闭：清空注册表，并释放本运行时打开的技能包句柄（目录感知，不影响其它 skillsDir 的缓存） */
    public synchronized void shutdown() {
        skillRegistry.clear();
        triggerIndex.clear();
        initialized = false;
        SkillBundle.invalidateDir(skillsDir);
    }

    /**
     * 取本运行时技能目录下的技能包（实例入口，多实例隔离）。
     * 未初始化时按声明里的技能目录懒加载（与 {@code AutoAgent.resolveActiveSkills} 同口径）；
     * 注意：调用方需持有 {@code holder.getRuntime()}，本方法不感知声明——
     * 懒加载的目录由调用方先经 {@link #init} 传入。
     */
    public SkillBundle getBundle(String skillName) {
        return SkillBundle.get(this.skillsDir, skillName);
    }

    /** 注册单个技能 */
    public void registerSkill(SkillEntity skill) {
        if (skill == null || StringUtils.isBlank(skill.getSlug())) {
            return;
        }
        skillRegistry.put(skill.getSlug(), skill);
        if (skill.getTriggers() != null) {
            for (String trigger : skill.getTriggers()) {
                triggerIndex.computeIfAbsent(trigger.toLowerCase(), k -> new ArrayList<>()).add(skill.getSlug());
            }
        }
    }

    /** 按 slug 获取技能 */
    public SkillEntity getSkill(String slug) {
        return skillRegistry.get(slug);
    }

    /** 获取所有已注册技能 */
    public Collection<SkillEntity> getAllSkills() {
        return skillRegistry.values();
    }

    /** 获取所有已注册技能的 slug 列表 */
    public List<String> getAllSkillSlugs() {
        return new ArrayList<>(skillRegistry.keySet());
    }

    /** 根据用户输入匹配技能（基于触发词） */
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

    /** 生成所有技能的摘要列表（用于系统提示词） */
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

    /** 获取技能的完整内容 */
    public String getSkillContent(String slug) {
        SkillEntity skill = skillRegistry.get(slug);
        if (skill == null) {
            return "技能不存在: " + slug;
        }
        return skill.getContent();
    }

    /** 检查是否已初始化 */
    public boolean isInitialized() {
        return initialized;
    }

    /** 本仓储的技能目录（未初始化返回 null） */
    public String getSkillsDir() {
        return skillsDir;
    }
}
