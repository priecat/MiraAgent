package net.itzq.mira.modules.ai.openapi;

import lombok.Data;

@Data
public class ApiParam {

    /** 参数在 HTTP 请求中的位置 */
    public enum In {
        PATH, QUERY, HEADER, BODY, COOKIE, FORM
    }

    /** 参数名（工具参数名 = HTTP 参数名） */
    private String name;

    /** 位置 */
    private In in;

    /** 是否必填 */
    private boolean required;

    /** 类型映射用的 Java Class（用于生成 JSON Schema type） */
    private Class<?> type;

    /** 描述 */
    private String description;
}
