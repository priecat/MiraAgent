package net.itzq.mira.modules.ai.openapi;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ApiOperation {

    /** 工具函数名，如 openapi_test */
    private String name;

    /** 给模型的简短描述 */
    private String summary;

    /** 详细描述 */
    private String description;

    /** HTTP 方法 GET/POST... */
    private String httpMethod;

    /** 路径模板，含 {param} 占位 */
    private String pathTemplate;

    /** 服务基址（可被 ImportConfig 覆盖） */
    private String serverUrl;

    /** 参数列表（已摊平，含 PATH/QUERY/HEADER/BODY） */
    private List<ApiParam> params;

    /** 静态/全局请求头（与运行期全局 header 提供者叠加） */
    private Map<String, String> headers;
}
