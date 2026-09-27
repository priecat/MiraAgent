package net.itzq.mira.modules.example;

import java.nio.file.Files;
import java.util.Arrays;

import net.itzq.mira.modules.ai.client.config.ModelApiConfig;
import net.itzq.mira.modules.ai.client.embedding.EmbeddingApiConfig;
import net.itzq.mira.modules.ai.client.embedding.EmbeddingProvider;
import net.itzq.mira.modules.ai.client.embedding.EmbeddingRegistry;
import net.itzq.mira.modules.ai.client.handle.OpenAICompatibleStreamEventHandler;
import net.itzq.mira.modules.config.AgentConfig;
import net.itzq.mira.modules.config.CredentialPolicy;
import net.itzq.mira.modules.config.GlobalConfigManager;
import net.itzq.mira.modules.config.SseClientConfig;
import net.itzq.mira.modules.config.ValidationReport;
import net.itzq.mira.modules.runtime.KernelRuntime;

/**
 * 配置中心用法示例（多实例写法）。
 *
 * <h3>与旧写法的区别</h3>
 * <ul>
 *   <li>旧：{@code GlobalConfigManager.config()} 静态单例 + {@code EmbeddingProviderManage} 静态 facade，
 *       全局唯一、多实例下互相覆盖；</li>
 *   <li>新：每个 {@link KernelRuntime} 持有一份**独立声明**（{@code rt.declaration()}）与一套注册表
 *       （{@code rt.modelRegistry()} / {@code rt.embeddingRegistry()}）。改声明后调
 *       {@code applyAllInstance()} 应用到本实例；导出/导入走 {@code rt.exportDeclaration(...)} /
 *       {@code rt.importDeclaration(...)}。</li>
 * </ul>
 *
 * <p>示例用占位 apiKey，网络调用仅用于演示"注册流程"（真实调用需要有效密钥）。</p>
 */
public class ConfigExample {

    private static final String CHAT_ALIAS = "gpt-main";
    private static final String EMBED_ALIAS = "text-embed";

    public static void main(String[] args) throws Exception {
        // 装配一个独立运行时：声明/注册表都随实例走
        KernelRuntime rt = KernelRuntime.builder()
                .name("config-example")
                .dataDir(Files.createTempDirectory("mira-config-example").toString())
                .build()
                .start();
        try {
            // 1) 代码构建配置并应用到本实例
            demoBuildAndApply(rt);

            // 2) 导出声明快照（本机自用原文 / 分发用引用两种凭据口径）
            String shareable = demoExport(rt);

            // 3) 从一个 JSON 声明导入并应用
            demoImportFromJson(rt, shareable);

            // 4) 配置生效后，经本实例的 Embedding 注册表取向量
            demoUseEmbedding(rt);
        } finally {
            rt.close();
        }
    }

    /**
     * 示例1：构建配置 → 应用到本实例。
     *
     * <p>声明域（{@link GlobalConfigManager}）只负责"世界有什么"；把这些值真正注册进各子系统
     * 由 {@code applyAllInstance()} 完成（模型 → {@code ModelRegistry}，Embedding → {@code EmbeddingRegistry}）。
     */
    private static void demoBuildAndApply(KernelRuntime rt) {
        System.out.println("===== 示例1：构建并应用配置（本实例）=====");

        ModelApiConfig chatModel = ModelApiConfig.builder()
                .alias(CHAT_ALIAS)
                .providerName("openai")
                .apiModelName("gpt-4o-mini")
                .apiHost("https://api.openai.com")
                .apiKey("sk-xxxx")
                .apiEndpoint("/v1/chat/completions")
                .sseEventHandler(OpenAICompatibleStreamEventHandler.class.getName())
                .build();

        EmbeddingApiConfig embedding = EmbeddingApiConfig.builder()
                .alias(EMBED_ALIAS)
                .apiUrl("https://api.openai.com/v1/embeddings")
                .apiKey("sk-xxxx")
                .model("text-embedding-3-small")
                .dimension(1536)
                .connectTimeout(30000)
                .readTimeout(60000)
                .build();

        SseClientConfig sse = SseClientConfig.builder()
                .connectTimeoutMs(15 * 1000)
                .readTimeoutMs(15 * 60 * 1000)
                .maxConnections(100)
                .pooledConnectionIdleTimeoutMs(60 * 1000)
                .keepAlive(false)
                .build();

        AgentConfig agentConfig = AgentConfig.builder()
                .defaultModel(CHAT_ALIAS)
                .defaultEmbedding(EMBED_ALIAS)
                .skillsDir("data-skills/skills")
                .build();

        // 写入**本实例**的声明，再应用到本实例注册表
        GlobalConfigManager decl = rt.declaration();
        decl.setAgentConfig(agentConfig);
        decl.setSseClientSimpleConfig(sse);
        decl.setModels(Arrays.asList(chatModel));
        decl.setEmbeddings(Arrays.asList(embedding));
        decl.applyAllInstance();

        System.out.println("默认模型        = " + rt.declaration().getAgentConfig().getDefaultModel());
        System.out.println("默认 Embedding  = " + rt.declaration().getAgentConfig().getDefaultEmbedding());
        System.out.println("技能目录        = " + rt.declaration().getAgentConfig().getSkillsDir());
        System.out.println("SSE 连接超时    = " + rt.declaration().getSseClientSimpleConfig().getConnectTimeoutMs() + "ms");
        System.out.println("已注册模型      = " + rt.modelRegistry().chatAliases());
        System.out.println("已注册向量别名  = " + rt.embeddingRegistry().aliases());
    }

    /**
     * 示例2：导出声明快照。
     *
     * <p>{@link CredentialPolicy#REFERENCE} 把密钥转成 {@code ${ENV:...}} 占位（分发用），
     * {@link CredentialPolicy#INLINE} 保留原文（本机自用）。
     */
    private static String demoExport(KernelRuntime rt) {
        System.out.println("\n===== 示例2：导出声明（分发用，密钥转引用）=====");
        String shareable = rt.exportDeclaration(CredentialPolicy.REFERENCE);
        System.out.println(shareable);
        return shareable;
    }

    /**
     * 示例3：从 JSON 声明导入并应用。返回 {@link ValidationReport}——"缺什么"一目了然。
     */
    private static void demoImportFromJson(KernelRuntime rt, String json) {
        System.out.println("\n===== 示例3：从 JSON 导入声明 =====");
        ValidationReport report = rt.importDeclaration(json);
        System.out.println("导入结果: " + report.summary());
        System.out.println("导入后模型数量   = " + rt.declaration().getModels().size());
        System.out.println("导入后 Embedding = " + rt.declaration().getEmbeddings().size());
    }

    /**
     * 示例4：经本实例的 Embedding 注册表取向量（替代旧的 {@code EmbeddingProviderManage.embed(...)}）。
     */
    private static void demoUseEmbedding(KernelRuntime rt) {
        System.out.println("\n===== 示例4：取 Embedding 向量 =====");
        EmbeddingRegistry registry = rt.embeddingRegistry();

        EmbeddingProvider provider = registry.getProvider(EMBED_ALIAS, false);
        if (provider != null) {
            System.out.println("已注册 Embedding provider: " + EMBED_ALIAS
                    + "（维度 " + provider.getDimension() + "）");
        }

        try {
            float[] vec = registry.embed(EMBED_ALIAS, "你好世界");
            System.out.println("向量维度: " + (vec != null ? vec.length : 0));
        } catch (Exception e) {
            // 占位 apiKey 无法真实调用接口，仅验证注册流程
            System.out.println("(占位 apiKey 无法真实调用，仅验证注册流程) " + e.getMessage());
        }
    }
}
