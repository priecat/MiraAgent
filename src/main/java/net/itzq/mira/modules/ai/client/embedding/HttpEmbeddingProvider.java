package net.itzq.mira.modules.ai.client.embedding;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 HTTP 的 Embedding 提供者
 *
 * 支持 OpenAI 兼容的 Embedding API
 *
 * @author tangzq
 */
public class HttpEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpEmbeddingProvider.class);

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final int dimension;
    private final int connectTimeout;
    private final int readTimeout;

    /**
     * 构造函数
     *
     * @param apiUrl   API 主机地址（如 https://api.openai.com）
     * @param apiKey    API Key
     * @param model     模型名称（如 text-embedding-3-small）
     * @param dimension 向量维度
     */
    public HttpEmbeddingProvider(String apiUrl, String apiKey, String model, int dimension) {
        this(apiUrl, apiKey, model, dimension, 30000, 60000);
    }

    /**
     * 构造函数（完整参数）
     *
     * @param apiUrl        API 主机地址
     * @param apiKey         API Key
     * @param model          模型名称
     * @param dimension      向量维度
     * @param connectTimeout 连接超时（毫秒）
     * @param readTimeout    读取超时（毫秒）
     */
    public HttpEmbeddingProvider(String apiUrl, String apiKey, String model, int dimension,
            int connectTimeout, int readTimeout) {
        if (apiUrl == null || apiUrl.isEmpty()) {
            throw new IllegalArgumentException("apiUrl cannot be null or empty");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey cannot be null or empty");
        }
        if (model == null || model.isEmpty()) {
            throw new IllegalArgumentException("model cannot be null or empty");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }

        // 移除尾部斜杠
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // 截断过长文本（DashScope 限制 33000 字符）
        if (text.length() > 32000) {
            log.warn("文本过长({}字符)，截断到32000字符", text.length());
            text = text.substring(0, 32000);
        }

        try {
            String endpoint = apiUrl;

            JSONObject body = new JSONObject();
            body.put("input", text);
            body.put("model", model);
            body.put("dimensions", dimension);  // 指定输出维度

            // 使用 Hutool 发起 HTTP 请求
            try (HttpResponse response = HttpRequest.post(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .setConnectionTimeout(connectTimeout)
                    .setReadTimeout(readTimeout)
                    .body(body.toJSONString())
                    .execute()) {

                int responseCode = response.getStatus();
                if (responseCode != 200) {
                    String errorBody = response.body();
                    log.error("Embedding API 错误: code={}, body={}", responseCode, errorBody);
                    return null;
                }

                String responseBody = response.body();
                JSONObject json = JSON.parseObject(responseBody);
                JSONArray data = json.getJSONArray("data");

                if (data != null && !data.isEmpty()) {
                    JSONArray embedding = data.getJSONObject(0).getJSONArray("embedding");
                    float[] result = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        result[i] = embedding.getFloat(i);
                    }
                    log.debug("Embedding 调用成功: textLen={}, dim={}", text.length(), result.length);
                    return result;
                }
            }

            log.warn("Embedding API 返回空数据");
            return null;

        } catch (Exception e) {
            log.error("Embedding 调用失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    // ==================== Getter ====================

    public String getApiUrl() {
        return apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModel() {
        return model;
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiHost;
        private String apiKey;
        private String model;
        private int dimension = 1536;
        private int connectTimeout = 30000;
        private int readTimeout = 60000;

        public Builder apiHost(String apiHost) {
            this.apiHost = apiHost;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder dimension(int dimension) {
            this.dimension = dimension;
            return this;
        }

        public Builder connectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder readTimeout(int readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public HttpEmbeddingProvider build() {
            return new HttpEmbeddingProvider(apiHost, apiKey, model, dimension, connectTimeout, readTimeout);
        }
    }
}
