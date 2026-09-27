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
 * OpenAICompatibleModerationService - OpenAI 兼容内容审查服务
 * <p>
 * 端点默认 {@code /v1/moderations}。同步请求-响应，返回上游原始 JSON，结构化携带状态码。
 * </p>
 *
 * @author tangzq
 */
@Slf4j
public class OpenAICompatibleModerationService {

    private static final String DEFAULT_ENDPOINT = "/v1/moderations";

    private final HttpSSEClient httpSSEClient;

    private final ModelApiConfig config;

    /** SSE 超时快照（多实例）：所属运行时的声明，请求时传给 HttpSSEClient */
    private final SseClientConfig sseTimeouts;

    public OpenAICompatibleModerationService(ModelApiConfig config) {
        this(config, HttpSSEClient.defaultTimeouts());
    }

    public OpenAICompatibleModerationService(ModelApiConfig config, SseClientConfig timeouts) {
        this.config = config;
        this.sseTimeouts = timeouts == null ? HttpSSEClient.defaultTimeouts() : timeouts;
        this.httpSSEClient = HttpSSEClient.getInstance();
    }

    /**
     * 内容审查（POST {apiHost}{apiEndpoint}，请求体为完整 JSON 字符串）
     *
     * @param jsonBody 上游请求体 JSON 原文（含 model/input 等）
     * @return HttpResp（status + bodyUtf8）
     */
    public HttpResp moderate(String jsonBody) {
        String url = config.getApiHost() + endpoint();
        return httpSSEClient.postJsonSyncDetailed(url, jsonBody, buildHeaders(), sseTimeouts);
    }

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
