package net.itzq.mira.modules.ai.http;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 单个 HTTP 请求工具的元数据定义。
 * 一个 HttpToolMeta 即一个可供模型调用的 HTTP 工具：URL 模板、HTTP 方法、参数与请求头等。
 */
@Data
public class HttpToolMeta {

    /** 工具函数名（唯一），如 http_get_user */
    private String name;

    /** 给模型的简短描述 */
    private String summary;

    /** 详细描述 */
    private String description;

    /** HTTP 方法 GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS */
    private String httpMethod;

    /** 路径模板，含 {param} 占位 */
    private String pathTemplate;

    /** 服务基址，与 pathTemplate 拼接为完整 URL */
    private String serverUrl;

    /** 参数列表（已摊平，含 PATH/QUERY/HEADER/BODY/FORM） */
    private List<HttpParam> params;

    /** 静态请求头（与运行期全局 header 提供者叠加） */
    private Map<String, String> headers;

    /** 单次请求超时（毫秒），<=0 时使用全局默认值 */
    private int timeoutMs;

    /** 响应体截断上限（字节），<=0 时使用全局默认值 */
    private int maxResponseBytes;
}
