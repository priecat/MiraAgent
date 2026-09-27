package net.itzq.mira.modules.vfs;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.workspace.Workspace;
import net.itzq.mira.modules.workspace.WorkspaceConfig;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 虚拟文件系统服务（编排运行时协议 · 实例组件）。
 *
 * <p>P5 实例化：每个内核运行时（KernelRuntime）持有一个服务实例，**数据目录随实例走**
 * （{@code <runtime.dataDir>/vfs} 由调用方给出）——同进程多实例的同名 vfsId 互不可见，
 * {@code setVfsId(historyId)} 语义不变（隔离由根目录保证，命名无需改）。
 *
 * <p>存量零迁移：默认运行时的 dataDir 指向现有目录，历史附件天然归默认实例。
 */
@Slf4j
public class VfsService {

    private volatile WorkspaceConfig config;

    /**
     * 配置来源（可选）：给定后 {@link #config()} 每次向其取值——
     * 用于"跟随声明工作空间配置"的场景（默认运行时 / 宿主），
     * 避免宿主后置 setWorkspaceConfig 时服务拿到旧对象。
     */
    private final java.util.function.Supplier<WorkspaceConfig> configProvider;

    public VfsService(WorkspaceConfig config) {
        this.config = config == null ? new WorkspaceConfig() : config;
        this.configProvider = null;
    }

    private VfsService(java.util.function.Supplier<WorkspaceConfig> provider) {
        this.configProvider = provider;
        WorkspaceConfig c = provider == null ? null : provider.get();
        this.config = c == null ? new WorkspaceConfig() : c;
    }

    /**
     * 跟随式构造：配置由提供者动态给出（如 {@code () -> declaration.getWorkspaceConfig()}）。
     * 适合"数据目录可能后置设置"的默认运行时。
     */
    public static VfsService following(java.util.function.Supplier<WorkspaceConfig> provider) {
        return new VfsService(provider);
    }

    /** 便捷构造：只用数据目录（Lucene 等其余参数取 WorkspaceConfig 默认值） */
    public VfsService(String dataDir) {
        WorkspaceConfig c = new WorkspaceConfig();
        c.setDataDir(dataDir);
        this.config = c;
        this.configProvider = null;
    }

    /** 当前配置（跟随式会实时取值；只读用途，如需改请用 {@link #reset}） */
    public WorkspaceConfig config() {
        if (configProvider != null) {
            WorkspaceConfig c = configProvider.get();
            if (c != null) {
                this.config = c;
            }
        }
        return config;
    }

    /** 重置配置（宿主启动装配 / 声明导入后重新绑定数据目录） */
    public void reset(WorkspaceConfig config) {
        this.config = config == null ? new WorkspaceConfig() : config;
        log.info("【VfsService】数据目录已绑定: {}", this.config.getDataDir());
    }

    /** 数据目录 */
    public String dataDir() {
        return config().getDataDir();
    }

    /**
     * 加载（或延迟创建）本服务数据目录下的会话工作空间。
     *
     * @param sessionId 32 位 UUID（无连字符）
     */
    public Workspace load(String sessionId) {
        return VFS.loadWith(config(), sessionId);
    }

    /** 同上，但返回具体类型 {@link VFS}（工具侧需要 VFS 专属方法时用） */
    public VFS loadVfs(String sessionId) {
        return VFS.loadWith(config(), sessionId);
    }

    /** 按外部 zip 文件创建只读工作空间 */
    public Workspace createZip(Path zipFile) throws IOException {
        return VFS.createZipWith(zipFile, config());
    }
}
