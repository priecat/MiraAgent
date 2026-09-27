package net.itzq.mira.modules.ai.skills;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.vfs.VFS;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

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

    /** 技能包内真实根前缀：裸包为 "/"，带顶层目录的包为 "/&lt;top&gt;"（懒探测，见 detectRootPrefix） */
    private volatile String rootPrefix = "/";

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
                SkillBundle bundle = new SkillBundle(name, vfs);
                bundle.detectRootPrefix();
                log.debug("SkillBundle 已创建: {} -> {} (root={})", name, zipPath, bundle.rootPrefix);
                return bundle;
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
     * 探测技能包的真实根前缀。
     *
     * <p>常见两种打包结构：
     * <ul>
     *   <li>裸包：{@code SKILL.md} 直接在 zip 根 → 前缀 {@code /}</li>
     *   <li>带顶层目录（如 GitHub 导出的 minimax-docx.zip）：唯一顶层目录下才有
     *       {@code SKILL.md} → 前缀 {@code /&lt;top&gt;}</li>
     * </ul>
     * 探测失败保持 {@code /}，由调用方按原有错误路径报错。
     */
    private void detectRootPrefix() {
        if (vfs.exists("/SKILL.md")) {
            rootPrefix = "/";
            return;
        }
        try (Stream<String> names = vfs.list("/")) {
            List<String> candidates = new ArrayList<>();
            for (String name : (Iterable<String>) names::iterator) {
                if (!vfs.isDirectory("/" + name)) {
                    continue;
                }
                if (vfs.exists("/" + name + "/SKILL.md")) {
                    candidates.add(name);
                }
            }
            if (candidates.size() == 1) {
                rootPrefix = "/" + candidates.get(0);
            } else if (candidates.size() > 1) {
                // 多个候选时优先取与 slug 同名的顶层目录
                rootPrefix = "/" + (candidates.contains(skillName) ? skillName : candidates.get(0));
            }
        } catch (Exception e) {
            log.warn("探测技能包根目录失败: {}", skillName, e);
        }
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
        String p = resolve(relativePath);
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
        return vfs.exists(resolve(relativePath));
    }

    /**
     * 相对路径 → VFS 绝对路径：拼上真实根前缀（兼容带顶层目录的技能包）
     */
    private String resolve(String relativePath) {
        String p = relativePath.startsWith("/") ? relativePath : "/" + relativePath;
        return "/".equals(rootPrefix) ? p : rootPrefix + p;
    }

    /**
     * 失效并关闭指定技能包的缓存实例（重新导入覆盖 zip 前调用）。
     *
     * <p>不失效的后果：旧 ZipFS 句柄一直开着——Windows 下覆盖写 zip 会被文件锁挡住；
     * 即使写入成功，{@link #get} 也命中旧缓存读到旧内容。
     */
    public static void invalidate(String skillName) {
        SkillBundle b = cache.remove(skillName);
        if (b != null) {
            try {
                b.vfs.close();
            } catch (Exception e) {
                log.warn("关闭技能包缓存异常: {}", b.skillName, e);
            }
        }
    }

    /**
     * 展开技能包到磁盘目录（等价解压 zip）：目标目录已存在时先清空，保证与包内容一致。
     *
     * <p>供 {@code expand_skill} 工具使用——技能内的二进制资源（图片/字体/可执行脚本等）
     * 无法通过 {@code read_skill_file} 直接读取，展开到磁盘后由 AI 用文件工具处理。
     *
     * @param targetDir 目标目录（一般形如 {@code <dataDir>/skills-expand/<skillName>}）
     * @return 解压的文件数
     */
    public int expandTo(Path targetDir) throws IOException {
        deleteRecursivelyQuietly(targetDir);
        int[] count = {0};
        copyDir("/", targetDir, count);
        return count[0];
    }

    /** 递归复制 VFS 目录到磁盘：vfsDir 是虚拟路径（/ 开头），diskDir 是磁盘目标 */
    private void copyDir(String vfsDir, Path diskDir, int[] count) throws IOException {
        Files.createDirectories(diskDir);
        try (Stream<String> names = vfs.list(vfsDir)) {
            for (String name : (Iterable<String>) names::iterator) {
                String vPath = (vfsDir.endsWith("/") ? vfsDir : vfsDir + "/") + name;
                Path dPath = diskDir.resolve(name).normalize();
                if (!dPath.startsWith(diskDir)) {
                    // 条目名非法（含 .. 等），防目录穿越，直接跳过
                    continue;
                }
                if (vfs.isDirectory(vPath)) {
                    copyDir(vPath, dPath, count);
                } else {
                    Files.write(dPath, vfs.readAllBytes(vPath));
                    count[0]++;
                }
            }
        }
    }

    private static void deleteRecursivelyQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignore) {
                    // 单个文件删除失败不阻断展开
                }
            });
        } catch (IOException ignore) {
            // 清空失败则保留旧内容，由覆盖写兜底
        }
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
