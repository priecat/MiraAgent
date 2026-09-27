package net.itzq.mira.modules.ai.client.handle;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.client.config.ModelApiConfig;
import net.itzq.mira.modules.ai.client.sse.HttpResp;
import net.itzq.mira.modules.ai.client.sse.HttpSSEClient;
import net.itzq.mira.modules.config.SseClientConfig;
import net.itzq.mira.core.utils.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAICompatibleImageService - OpenAI 兼容图像生成服务
 * <p>
 * 端点默认 {@code /v1/images/generations}（由注册时的 ModelApiConfig.apiEndpoint 决定）。
 * 同步请求-响应，返回上游原始 JSON（url 或 b64_json），结构化携带状态码。
 * </p>
 *
 * @author tangzq
 */
@Slf4j
public class OpenAICompatibleImageService {

    private static final String DEFAULT_ENDPOINT = "/v1/images/generations";

    private final HttpSSEClient httpSSEClient;

    private final ModelApiConfig config;

    /** SSE 超时快照（多实例）：所属运行时的声明，请求时传给 HttpSSEClient */
    private final SseClientConfig sseTimeouts;

    public OpenAICompatibleImageService(ModelApiConfig config) {
        this(config, HttpSSEClient.defaultTimeouts());
    }

    public OpenAICompatibleImageService(ModelApiConfig config, SseClientConfig timeouts) {
        this.config = config;
        this.sseTimeouts = timeouts == null ? HttpSSEClient.defaultTimeouts() : timeouts;
        this.httpSSEClient = HttpSSEClient.getInstance();
    }

    /**
     * 图像生成（POST {apiHost}{apiEndpoint}，请求体为完整 JSON 字符串）
     *
     * @param jsonBody 上游请求体 JSON 原文（含 model/prompt/size 等）
     * @return HttpResp（status + bodyUtf8）
     */
    public HttpResp generate(String jsonBody) {
        String url = config.getApiHost() + endpoint();
        return httpSSEClient.postJsonSyncDetailed(url, jsonBody, buildHeaders(), sseTimeouts);
    }

    /**
     * 模型覆盖：将请求体中的 model 替换为本渠道配置的 apiModelName（透传原文时由调用方自行处理，此方法备用）
     */
    private String endpoint() {
        String ep = config.getApiEndpoint();
        return StringUtils.isNotBlank(ep) ? ep : DEFAULT_ENDPOINT;
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> apiHeaders = config.getApiHeaders();
        if (apiHeaders == null) {
            apiHeaders = new LinkedHashMap<>();
            if (StringUtils.isNotBlank(config.getApiKey())) {
                apiHeaders.put("Authorization", "Bearer " + config.getApiKey());
            }
        }
        return apiHeaders;
    }
}
