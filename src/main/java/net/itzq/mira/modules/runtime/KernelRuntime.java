package net.itzq.mira.modules.runtime;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.client.config.ModelRegistry;
import net.itzq.mira.modules.ai.client.embedding.EmbeddingRegistry;
import net.itzq.mira.modules.ai.persistence.PersistencePort;
import net.itzq.mira.modules.ai.skills.SkillRepository;
import net.itzq.mira.modules.ai.tool.ToolRegistry;
import net.itzq.mira.modules.config.CredentialPolicy;
import net.itzq.mira.modules.config.GlobalConfigManager;
import net.itzq.mira.modules.config.ValidationReport;
import net.itzq.mira.modules.vfs.VfsService;
import net.itzq.mira.modules.workspace.WorkspaceConfig;
import net.itzq.mira.modules.workspace.WorkspaceService;

/**
 * 内核运行时（编排运行时协议 · 实例化）。
 *
 * <p>同一 JVM 内可并存多个互相隔离的内核运行时——每个实例拥有<strong>独立</strong>的：
 * 声明配置、模型服务注册表、embedding 注册表、工具注册表、VFS 数据目录、磁盘工作空间目录、技能清单；
 * 外加可选挂载的 {@link PersistencePort}（不挂 = <b>即用即释放</b>：跑完即弃、无残留）。
 *
 * <pre>
 * KernelRuntime rt = KernelRuntime.builder()
 *         .name("rt-a")
 *         .dataDir("D:/rt-a")            // VFS 数据目录 → &lt;dataDir&gt;/vfs 语义同现状
 *         .declarationJson(json)          // 声明快照导入（编排包里的 declaration 段）
 *         .persistencePort(port)          // 可选：挂持久化；不挂即用即释放
 *         .start();
 * ...
 * rt.close();                             // 释放注册表与资源
 * </pre>
 *
 * <p><b>兼容期</b>：静态 facade（{@code GlobalConfigManager.config()} /
 * {@code ApiProviderManage} / {@code FCUtil} / {@code EmbeddingProviderManage} /
 * {@code SkillManager} / {@code VFS}）全部委托 {@link #defaultRuntime()} 的实例，
 * 存量代码零改动；调用点逐步迁移到 {@code holder.getRuntime()} 后 facade 才可删除。
 */
@Slf4j
public final class KernelRuntime implements AutoCloseable {

    private static volatile KernelRuntime DEFAULT;

    private final String name;
    private final String dataDir;

    private final GlobalConfigManager declaration;
    private final ModelRegistry modelRegistry;
    private final EmbeddingRegistry embeddingRegistry;
    private final ToolRegistry toolRegistry;
    private final VfsService vfs;
    private final WorkspaceService workspace;
    private final SkillRepository skills;
    private volatile PersistencePort persistencePort;

    private volatile boolean started;
    private volatile boolean closed;

    private KernelRuntime(Builder b) {
        this.name = b.name == null ? "runtime" : b.name;
        this.dataDir = b.dataDir;
        this.declaration = new GlobalConfigManager();
        this.declaration.bindRuntime(this);
        this.modelRegistry = new ModelRegistry();
        this.embeddingRegistry = new EmbeddingRegistry();
        this.toolRegistry = new ToolRegistry();
        this.modelRegistry.bindToolRegistry(this.toolRegistry);
        // 反向绑定：注册表由此能取到本实例声明（SSE 超时等 per-runtime 配置）
        this.toolRegistry.bindRuntime(this);
        // 跟随声明的工作空间配置：宿主后置 setWorkspaceConfig 也能被服务感知
        this.vfs = b.vfsConfig != null
                ? new VfsService(b.vfsConfig)
                : VfsService.following(workspaceConfigSupplier());
        // 磁盘工作空间服务（与 vfs 对称）：数据目录同样跟随运行时/声明
        this.workspace = b.vfsConfig != null
                ? new WorkspaceService(b.vfsConfig)
                : WorkspaceService.following(workspaceConfigSupplier());
        this.skills = new SkillRepository();
        this.persistencePort = b.persistencePort == null ? PersistencePort.NOOP : b.persistencePort;

        if (b.declarationJson != null && !b.declarationJson.trim().isEmpty()) {
            this.declaration.importFromJson(b.declarationJson);
        }
    }

    /**
     * 工作空间配置来源（供 vfs / workspace 两个服务共用）：
     * 声明里的 workspaceConfig 优先；其未给数据目录时回落运行时 {@code dataDir}（独立实例常用形态）。
     */
    private java.util.function.Supplier<WorkspaceConfig> workspaceConfigSupplier() {
        return () -> {
            WorkspaceConfig wc = declaration.getWorkspaceConfig();
            if (wc != null && (wc.getDataDir() == null || wc.getDataDir().isEmpty()) && dataDir != null) {
                WorkspaceConfig fallback = new WorkspaceConfig();
                fallback.setDataDir(dataDir);
                return fallback;
            }
            return wc;
        };
    }

    // ================================================================= 默认运行时

    /**
     * 默认运行时（存量行为承载者）：静态 facade 的委托目标，懒创建。
     * 首次访问会完成 {@link #start()}（扫描内核默认工具、应用声明）。
     */
    public static KernelRuntime defaultRuntime() {
        KernelRuntime rt = DEFAULT;
        if (rt != null) {
            return rt;
        }
        synchronized (KernelRuntime.class) {
            if (DEFAULT == null) {
                DEFAULT = new Builder().name("default").build().start();
                log.info("【KernelRuntime】默认运行时已创建: dataDir={}", DEFAULT.dataDir());
            }
            return DEFAULT;
        }
    }

    /**
     * 安装默认运行时（宿主启动时调用一次）：让静态 facade 指向这个已配置的实例
     * （数据目录 / 持久化端口）。已存在且未关闭的默认实例会被替换。
     */
    public static synchronized void installDefault(KernelRuntime runtime) {
        if (runtime == null) {
            return;
        }
        DEFAULT = runtime;
        log.info("【KernelRuntime】默认运行时已安装: name={}, dataDir={}", runtime.name(), runtime.dataDir());
    }

    /** 是否已有默认运行时（不触发创建） */
    public static boolean hasDefault() {
        return DEFAULT != null;
    }

    // ================================================================= 生命周期

    /** 启动：扫描内核默认工具、应用声明里的模型/embedding 到本实例注册表（幂等） */
    public KernelRuntime start() {
        if (started) {
            return this;
        }
        synchronized (this) {
            if (started) {
                return this;
            }
            toolRegistry.initDefaultTools();
            declaration.applyAllInstance();
            started = true;
            closed = false;
            log.info("【KernelRuntime】已启动: name={}, tools={}, models={}",
                    name, toolRegistry.size(), modelRegistry.chatAliases().size());
        }
        return this;
    }

    /** 关闭：清空寄存器并释放（即用即释放形态跑完即弃，不留全局残留） */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            toolRegistry.clear();
            modelRegistry.clear();
            embeddingRegistry.clear();
            skills.shutdown();
            started = false;
            closed = true;
            log.info("【KernelRuntime】已关闭: name={}", name);
        }
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isClosed() {
        return closed;
    }

    // ================================================================= 声明快照

    /** 导出本实例声明（编排运行时协议 · declaration） */
    public String exportDeclaration(CredentialPolicy policy) {
        return declaration.exportToJson(policy == null ? CredentialPolicy.INLINE : policy);
    }

    /** 导入声明并应用进本实例（返回校验报告；不影响其他实例） */
    public ValidationReport importDeclaration(String json) {
        return declaration.importFromJson(json);
    }

    // ================================================================= 访问器

    public String name() {
        return name;
    }

    public String dataDir() {
        return dataDir;
    }

    public GlobalConfigManager declaration() {
        return declaration;
    }

    public ModelRegistry modelRegistry() {
        return modelRegistry;
    }

    public EmbeddingRegistry embeddingRegistry() {
        return embeddingRegistry;
    }

    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    public VfsService vfs() {
        return vfs;
    }

    /** 磁盘工作空间服务（与 {@link #vfs()} 对称）：{@code rt.workspace().load(sessionId)} 取会话空间 */
    public WorkspaceService workspace() {
        return workspace;
    }

    public SkillRepository skills() {
        return skills;
    }

    public PersistencePort persistencePort() {
        return persistencePort;
    }

    /** 挂载/替换持久化端口（不挂 = NOOP = 即用即释放） */
    public void setPersistencePort(PersistencePort port) {
        this.persistencePort = port == null ? PersistencePort.NOOP : port;
    }

    // ================================================================= Builder

    public static Builder builder() {
        return new Builder();
    }

    /** 运行时构造器 */
    public static final class Builder {
        private String name;
        private String dataDir;
        private String declarationJson;
        private PersistencePort persistencePort;
        private WorkspaceConfig vfsConfig;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** 运行时数据目录（独立实例的 VFS/工作空间根） */
        public Builder dataDir(String dataDir) {
            this.dataDir = dataDir;
            return this;
        }

        /** 初始声明快照（编排包里的 declaration 段 JSON；可空） */
        public Builder declarationJson(String declarationJson) {
            this.declarationJson = declarationJson;
            return this;
        }

        /** 持久化端口（可空 = 即用即释放） */
        public Builder persistencePort(PersistencePort persistencePort) {
            this.persistencePort = persistencePort;
            return this;
        }

        /** 显式指定工作空间配置（不指定则跟随声明，声明为空时回落 {@link #dataDir}） */
        public Builder vfsConfig(WorkspaceConfig vfsConfig) {
            this.vfsConfig = vfsConfig;
            return this;
        }

        /** 构造（未启动；需要工具/注册表就绪请接着调 {@link KernelRuntime#start()}） */
        public KernelRuntime build() {
            return new KernelRuntime(this);
        }

        /** 构造并启动 */
        public KernelRuntime start() {
            return build().start();
        }
    }
}
