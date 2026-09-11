package net.itzq.mira.modules.ai.client.handle;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.client.config.ModelApiConfig;
import net.itzq.mira.modules.ai.client.sse.FormPart;
import net.itzq.mira.modules.ai.client.sse.HttpResp;
import net.itzq.mira.modules.ai.client.sse.HttpSSEClient;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAICompatibleAudioService - OpenAI 兼容语音服务
 * <p>
 * 两个端点均为同步请求-响应（非 SSE 流）：
 * <ul>
 *   <li>transcription：POST {apiHost}{apiEndpoint}，multipart/form-data 上传音频，返回 JSON</li>
 *   <li>speech：POST {apiHost}{apiEndpoint}，JSON 请求体，返回二进制音频</li>
 * </ul>
 * 同一 Service 实例对应一个 endpoint（transcription 与 speech 需分别注册，alias 不同）。
 * </p>
 *
 * @author tangzq
 */
@Slf4j
public class OpenAICompatibleAudioService {

    private static final String DEFAULT_ENDPOINT = "/v1/audio/transcriptions";

    private final HttpSSEClient httpSSEClient;

    private final ModelApiConfig config;

    public OpenAICompatibleAudioService(ModelApiConfig config) {
        this.config = config;
        this.httpSSEClient = HttpSSEClient.getInstance();
    }

    /**
     * 语音转文字（multipart/form-data：file + model + 可选 language/response_format 等）
     *
     * @param fileBytes   音频文件字节
     * @param filename    音频文件名（含扩展名）
     * @param model       语音识别模型名（如 whisper-1）
     * @param extraParts  附加表单字段（language/response_format/prompt/temperature 等），可为 null
     * @return HttpResp（status + bodyUtf8）
     */
    public HttpResp transcribe(byte[] fileBytes, String filename, String model, Map<String, String> extraParts) {
        Map<String, String> headers = buildHeaders();
        java.util.List<FormPart> parts = new java.util.ArrayList<>();
        parts.add(FormPart.file("file", fileBytes, filename, null));
        parts.add(FormPart.field("model", model));
        if (extraParts != null) {
            for (Map.Entry<String, String> e : extraParts.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    parts.add(FormPart.field(e.getKey(), e.getValue()));
                }
            }
        }
        String url = config.getApiHost() + endpoint();
        return httpSSEClient.postMultipartSync(url, parts, headers);
    }

    /**
     * 文字转语音（JSON 请求体：model/input/voice/response_format/speed 等）
     *
     * @param jsonBody 上游请求体 JSON 原文
     * @return HttpResp（status + bodyBytes + contentType）
     */
    public HttpResp speech(String jsonBody) {
        String url = config.getApiHost() + endpoint();
        return httpSSEClient.postJsonSyncBytes(url, jsonBody, buildHeaders());
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
