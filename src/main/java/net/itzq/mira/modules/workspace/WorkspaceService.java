package net.itzq.mira.modules.workspace;

import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

/**
 * 磁盘工作空间服务（编排运行时协议 · 实例组件）。
 *
 * <p>与 {@link net.itzq.mira.modules.vfs.VfsService} 对称：每个内核运行时（{@code KernelRuntime}）
 * 持有一个服务实例，<b>数据目录随实例走</b>——同进程多实例下的同名 sessionId 互不可见。
 *
 * <p>相比 {@code FileWorkspace} 的静态 {@code init(config)}（进程级一次性、多实例会互相干扰），
 * 本服务把配置绑到实例上，并提供与声明联动的"跟随式"构造（宿主后置改 workspaceConfig 也能感知）。
 *
 * <pre>{@code
 * // 经运行时取（推荐，与 rt.vfs() 对称）
 * try (Workspace wk = rt.workspace().load(sessionId)) {
 *     wk.write("/data/a.txt", "hello");
 * }
 * }</pre>
 */
@Slf4j
public class WorkspaceService {

    private volatile WorkspaceConfig config;

    /**
     * 配置来源（可选）：给定后 {@link #config()} 每次向其取值——
     * 用于"跟随声明工作空间配置"的场景，避免宿主后置 setWorkspaceConfig 时服务拿到旧对象。
     */
    private final Supplier<WorkspaceConfig> configProvider;

    public WorkspaceService(WorkspaceConfig config) {
        this.config = config == null ? new WorkspaceConfig() : config;
        this.configProvider = null;
    }

    private WorkspaceService(Supplier<WorkspaceConfig> provider) {
        this.configProvider = provider;
        WorkspaceConfig c = provider == null ? null : provider.get();
        this.config = c == null ? new WorkspaceConfig() : c;
    }

    /**
     * 跟随式构造：配置由提供者动态给出（如 {@code () -> declaration.getWorkspaceConfig()}）。
     * 适合"数据目录可能后置设置"的默认运行时。
     */
    public static WorkspaceService following(Supplier<WorkspaceConfig> provider) {
        return new WorkspaceService(provider);
    }

    /** 便捷构造：只用数据目录 */
    public WorkspaceService(String dataDir) {
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
        log.info("【WorkspaceService】数据目录已绑定: {}", this.config.getDataDir());
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
    public FileWorkspace load(String sessionId) {
        return FileWorkspace.of(config(), sessionId);
    }
}
