package net.itzq.mira.modules.ai.client.sse;

import lombok.Data;

/**
 * HttpResp - 轻量 HTTP 响应结构
 * <p>
 * 结构化携带状态码与响应体（文本 / 二进制），用于 LLM 中转等需要
 * 感知上游 HTTP 状态码与原始响应的场景（替代丢弃状态码的 {@code String} 返回）。
 * </p>
 *
 * @author tangzq
 */
@Data
public class HttpResp {

    /** HTTP 状态码 */
    private int status;

    /** 文本响应体（JSON 等文本端点，UTF-8 解码） */
    private String bodyUtf8;

    /** 二进制响应体（audio/speech 等二进制端点；与 bodyUtf8 互斥） */
    private byte[] bodyBytes;

    /** 响应 Content-Type（透传给客户端，如 audio/mpeg） */
    private String contentType;

    public HttpResp() {
    }

    public HttpResp(int status, String bodyUtf8, String contentType) {
        this.status = status;
        this.bodyUtf8 = bodyUtf8;
        this.contentType = contentType;
    }

    public HttpResp(int status, byte[] bodyBytes, String contentType) {
        this.status = status;
        this.bodyBytes = bodyBytes;
        this.contentType = contentType;
    }

    /** 是否为 2xx 成功响应 */
    public boolean isOk() {
        return status >= 200 && status < 300;
    }

    /** 取文本响应体（无则返回空串） */
    public String body() {
        return bodyUtf8 == null ? "" : bodyUtf8;
    }
}
