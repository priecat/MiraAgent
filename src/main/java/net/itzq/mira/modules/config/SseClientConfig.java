package net.itzq.mira.modules.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HttpSSEClient 客户端配置（对应 HttpSSEClient#setClientConfig / getClientConfig 的各项参数）
 *
 * @author tangzq
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SseClientConfig {

    /** 连接超时（毫秒），默认 15 秒 */
    @Builder.Default
    private int connectTimeoutMs = 15 * 1000;

    /** 读取超时（毫秒），默认 15 分钟。SSE 长连接场景不宜过短 */
    @Builder.Default
    private int readTimeoutMs = 15 * 60 * 1000;

    /** 最大连接数（所有目标主机合计），默认 100 */
    @Builder.Default
    private int maxConnections = 100;

    /** 连接池空闲超时（毫秒），默认 60 秒 */
    @Builder.Default
    private int pooledConnectionIdleTimeoutMs = 60 * 1000;

    /** 是否保持长连接，SSE 场景不建议开启，默认 false */
    @Builder.Default
    private boolean keepAlive = false;
}
