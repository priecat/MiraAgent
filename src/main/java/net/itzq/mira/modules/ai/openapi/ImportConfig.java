package net.itzq.mira.modules.ai.openapi;

import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Data
public class ImportConfig {

    /** 覆盖文档里的 server 基址；为空则用文档推导 */
    private String baseUrlOverride;

    /** 工具名前缀，默认 openapi；同时用于批量卸载 */
    private String namespace = "openapi";

    /** 单次请求超时（毫秒） */
    private int timeoutMs = 10000;

    /** 响应体截断上限（字节） */
    private int maxResponseBytes = 64 * 1024;

    /** 运行期全局请求头提供者（如 accessTicket/Authorization），与静态头叠加 */
    private Supplier<Map<String, String>> headerProvider;

    /** 静态请求头 */
    private Map<String, String> staticHeaders;

    /** 仅导入包含这些 tag 的接口（为空表示不过滤） */
    private List<String> includeTags;

    /** 排除这些 tag 的接口 */
    private List<String> excludeTags;
}
