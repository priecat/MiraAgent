package net.itzq.mira.modules.ai.client.embedding;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Embedding 模型注册配置
 *
 * 用于注册 Embedding 服务提供者。
 * 字段均为可变字段并提供无参构造，支持 JSON 序列化/反序列化。
 *
 * @author tangzq
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingApiConfig {

    /** 别名（注册与获取实例的唯一标识） */
    private String alias;

    /** API 地址（完整 endpoint，如 https://api.openai.com/v1/embeddings） */
    private String apiUrl;

    /** API Key */
    private String apiKey;

    /** 模型名称（如 text-embedding-3-small） */
    private String model;

    /** 向量维度 */
    @Builder.Default
    private int dimension = 1536;

    /** 连接超时（毫秒） */
    @Builder.Default
    private int connectTimeout = 30000;

    /** 读取超时（毫秒） */
    @Builder.Default
    private int readTimeout = 60000;
}
