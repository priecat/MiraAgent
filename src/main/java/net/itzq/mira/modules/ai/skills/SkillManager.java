package net.itzq.mira.modules.ai.skills;

import net.itzq.mira.modules.runtime.KernelRuntime;

import java.util.Collection;
import java.util.List;

/**
 * SkillManager —— 技能仓储的**兼容 facade**（P5 实例化后保留）。
 *
 * <p>P5 起仓储是实例组件 {@link SkillRepository}（每个 {@code KernelRuntime} 一份，
 * 可指向不同 skillsDir）。本类静态方法全部委托默认运行时的仓储，存量调用点零改动。
 *
 * @deprecated P5：改用 {@code holder.getRuntime().skills()}。
 */
@Deprecated
public class SkillManager {

    private SkillManager() {
    }

    private static SkillRepository repository() {
        return KernelRuntime.defaultRuntime().skills();
    }

    /** 兼容入口：返回默认运行时的技能仓储（原单例语义的等价物） */
    public static SkillRepository getInstance() {
        return repository();
    }

    /** 初始化默认运行时的技能仓储（幂等） */
    public static void init(String skillsDirPath) {
        repository().init(skillsDirPath);
    }

    /** 关闭默认运行时的技能仓储 */
    public static void shutdown() {
        repository().shutdown();
    }

    public static void registerSkill(SkillEntity skill) {
        repository().registerSkill(skill);
    }

    public static SkillEntity getSkill(String slug) {
        return repository().getSkill(slug);
    }

    public static Collection<SkillEntity> getAllSkills() {
        return repository().getAllSkills();
    }

    public static List<String> getAllSkillSlugs() {
        return repository().getAllSkillSlugs();
    }

    public static List<SkillEntity> matchSkills(String userInput) {
        return repository().matchSkills(userInput);
    }

    public static String buildSkillsSummary() {
        return repository().buildSkillsSummary();
    }

    public static String getSkillContent(String slug) {
        return repository().getSkillContent(slug);
    }

    public static boolean isInitialized() {
        return repository().isInitialized();
    }
}
