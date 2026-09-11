package net.itzq.mira.modules.config;

import com.alibaba.fastjson2.JSONObject;
import net.itzq.mira.modules.ai.client.config.ModelApiConfig;
import net.itzq.mira.modules.ai.client.embedding.EmbeddingProviderManage;
import net.itzq.mira.modules.ai.client.embedding.EmbeddingApiConfig;
import net.itzq.mira.modules.ai.client.handle.OpenAICompatibleStreamEventHandler;

import java.util.Arrays;
import java.util.Collections;

/**
 * 全局配置用法示例
 *
 * <p>演示如何通过 {@link GlobalConfigManager} 统一配置各子系统（模型 / Embedding / SSE 客户端 / 技能目录），
 * 以及如何导入 / 导出 JSON 配置、通过别名获取实例并使用。</p>
 *
 * <p>直接运行 {@code main} 方法可观察完整流程（注意：实际调用 AI 接口需真实可用的 apiKey，
 * 本示例用占位符 {@code sk-xxxx}，网络调用部分仅验证注册流程）。</p>
 *
 * @author tangzq
 */
public class ConfigExample {

    public static void main(String[] args) {
        // 1) 代码构建配置并通过导入 JSON 一次性应用到各子系统
        demoBuildAndApply();

        // 2) 导出当前配置为 JSON（逐项 put 结构）
        demoExport();

        // 3) 从 JSON 字符串导入并应用（手动构造 JSON 模板）
        demoImportFromJson();

        // 4) 配置生效后，通过别名获取 Embedding 实例并取向量
        demoUseEmbedding();
    }

    /**
     * 示例 1：用 builder 构建一份完整配置，设置到管理器，再通过 importFromJson 应用
     *
     * <p>说明：{@code setXxx} 只把值存入管理器；真正注册到 {@code ApiProviderManage} /
     * {@code EmbeddingProviderManage} 的动作发生在 {@link GlobalConfigManager#importFromJson(String)} 内部。
     * 这里用「导出再导入」的方式触发应用，等价于让配置生效。</p>
     */
    private static void demoBuildAndApply() {
        System.out.println("===== 示例1：构建并应用全局配置 =====");

        // 对话模型（可注册多个，defaultModel 指定默认别名）
        ModelApiConfig chatModel = ModelApiConfig.builder()
                .alias("gpt-main")
                .providerName("openai")
                .apiModelName("gpt-4o-mini")
                .apiHost("https://api.openai.com")
                .apiKey("sk-xxxx")
                .apiEndpoint("/v1/chat/completions")
                .apiHeaders(Collections.singletonMap("X-App", "qai-runtime"))
                .sseEventHandler(OpenAICompatibleStreamEventHandler.class.getName())
                .build();

        // Embedding 模型
        EmbeddingApiConfig embedding = EmbeddingApiConfig.builder()
                .alias("text-embed")
                .apiUrl("https://api.openai.com/v1/embeddings")
                .apiKey("sk-xxxx")
                .model("text-embedding-3-small")
                .dimension(1536)
                .connectTimeout(30000)
                .readTimeout(60000)
                .build();

        // SSE 客户端参数
        SseClientConfig sse = SseClientConfig.builder()
                .connectTimeoutMs(15 * 1000)
                .readTimeoutMs(15 * 60 * 1000)
                .maxConnections(100)
                .pooledConnectionIdleTimeoutMs(60 * 1000)
                .keepAlive(false)
                .build();

//        AsyncHttpClientConfig sseFull = Dsl.config()
//                .setConnectTimeout(15 * 1000)
//                .setReadTimeout(15 * 60 * 1000)
//                .setRequestTimeout(15 * 60 * 1000)
//                .setMaxConnections(100)
//                .setPooledConnectionIdleTimeout(60 * 1000)
//                .setKeepAlive(false)
//                .build();

        // 全局入口配置
        AgentConfig agentConfig = AgentConfig.builder()
                .defaultModel("gpt-main")
                .defaultEmbedding("text-embed")
                .skillsDir("data-skills/skills")
                .build();

        // 统一设置到全局管理器
        GlobalConfigManager gcm = GlobalConfigManager.config();
        gcm.setAgentConfig(agentConfig);
        gcm.setSseClientSimpleConfig(sse);
        gcm.setModels(Arrays.asList(chatModel));
        gcm.setEmbeddings(Arrays.asList(embedding));

        // 通过「导出 -> 导入」触发 applyAll（注册到各子系统）
        gcm.importFromJson(gcm.exportToJson());

        System.out.println("默认模型=" + GlobalConfigManager.config().getAgentConfig().getDefaultModel());
        System.out.println("默认 Embedding=" + GlobalConfigManager.config().getAgentConfig().getDefaultEmbedding());
        System.out.println("技能目录=" + GlobalConfigManager.config().getAgentConfig().getSkillsDir());
        System.out.println("SSE 连接超时=" + GlobalConfigManager.config().getSseClientSimpleConfig().getConnectTimeoutMs() + "ms");
        System.out.println();
    }

    /**
     * 示例 2：导出当前配置为 JSON（逐项 put 结构），用于备份 / 迁移
     */
    private static String demoExport() {
        System.out.println("===== 示例2：导出配置为 JSON =====");
        String json = GlobalConfigManager.config().exportToJson();
        System.out.println(json);
        System.out.println();
        return json;
    }

    /**
     * 示例 3：从 JSON 字符串导入并应用（这里用手写 {@link JSONObject} 构造一份模板 JSON）
     */
    private static void demoImportFromJson() {
        System.out.println("===== 示例3：从 JSON 导入并应用 =====");

        String json = demoExport();
        System.out.println("模板 JSON：\n" + json);

        GlobalConfigManager.config().importFromJson(json);

        // 通过静态 getter 读取导入后的值
        System.out.println("导入后默认模型=" + GlobalConfigManager.config().getAgentConfig().getDefaultModel());
        System.out.println("导入后模型数量=" + GlobalConfigManager.config().getModels().size());
        System.out.println("导入后 Embedding 数量=" + GlobalConfigManager.config().getEmbeddings().size());
        System.out.println();
    }

    /**
     * 示例 4：配置生效后，通过别名获取 Embedding 实例并取向量
     */
    private static void demoUseEmbedding() {
        System.out.println("===== 示例4：通过别名获取 Embedding 向量 =====");

        // 1. 检查 provider 是否已注册（importFromJson 已注册）
        if (EmbeddingProviderManage.getProvider("text-embed") != null) {
            System.out.println("已注册 Embedding provider: text-embed");
        }

        // 2. 通过别名取向量（默认别名也可：EmbeddingProviderManage.embed("你好世界")）
        try {
            float[] vec = EmbeddingProviderManage.embed("text-embed", "你好世界");
            System.out.println("向量维度: " + (vec != null ? vec.length : 0));
        } catch (Exception e) {
            // 占位 apiKey 无法真实调用接口，仅验证注册流程
            System.out.println("(占位 apiKey 无法真实调用，仅验证注册流程) " + e.getMessage());
        }
        System.out.println();
    }


}
