package net.itzq.mira.modules.ai.skills;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.vfs.VFS;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能包，VFS包装类，表示一个skill的zip包
 * <p>
 * 使用实例缓存：每个 skillName 只打开一次 ZipFS，后续并发读取。
 * ZipFS 不支持并发打开（FileSystemAlreadyExistsException），但支持并发读取。
 * SkillBundle 只读，无需写同步。
 */
@Slf4j
public class SkillBundle {

    private static volatile String globalSkillDir;

    /** 实例缓存：skillName -> SkillBundle */
    private static final ConcurrentHashMap<String, SkillBundle> cache = new ConcurrentHashMap<>();

    private final String skillName;
    private final VFS vfs;

    /**
     * 设置全局技能目录（SkillManager.init 时调用）
     */
    public static void setSkillDir(String dir) {
        globalSkillDir = dir;
    }

    /**
     * 获取或创建 SkillBundle（缓存）
     * computeIfAbsent 保证同一 skill 只创建一次 VFS（只 openZip 一次）
     */
    public static SkillBundle get(String skillName) {
        return cache.computeIfAbsent(skillName, name -> {
            Path zipPath = Paths.get(globalSkillDir, name + ".zip");
            try {
                VFS vfs = VFS.createZip(zipPath);
                log.debug("SkillBundle 已创建: {} -> {}", name, zipPath);
                return new SkillBundle(name, vfs);
            } catch (IOException e) {
                throw new UncheckedIOException("无法打开技能包: " + name, e);
            }
        });
    }

    private SkillBundle(String skillName, VFS vfs) {
        this.skillName = skillName;
        this.vfs = vfs;
    }

    /**
     * 读取SKILL.md
     */
    public String readSkillMd() {
        return readFile("/SKILL.md");
    }

    /**
     * 读取 _skillhub_meta.json，不存在返回 null
     */
    public String readMeta() {
        if (!exists("/_skillhub_meta.json")) {
            return null;
        }
        return readFile("/_skillhub_meta.json");
    }

    /**
     * 读取技能包内文件
     * 并发安全：VFS.getFsForRead() 首次 synchronized openZip，后续走无锁 fast path
     */
    public String readFile(String relativePath) {
        String p = relativePath.startsWith("/") ? relativePath : "/" + relativePath;
        try {
            return vfs.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException("读取技能文件失败: " + p, e);
        }
    }

    /**
     * 判断文件是否存在
     */
    public boolean exists(String relativePath) {
        String p = relativePath.startsWith("/") ? relativePath : "/" + relativePath;
        return vfs.exists(p);
    }

    /**
     * 关闭所有缓存的 bundle（应用退出时调用）
     */
    public static void closeAll() {
        cache.values().forEach(b -> {
            try {
                b.vfs.close();
            } catch (Exception e) {
                log.warn("关闭SkillBundle异常: {}", b.skillName, e);
            }
        });
        cache.clear();
    }
}
