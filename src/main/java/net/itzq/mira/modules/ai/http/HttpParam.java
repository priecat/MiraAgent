package net.itzq.mira.modules.ai.http;

import lombok.Data;

@Data
public class HttpParam {

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

    /**
     * 参数类型（JSON Schema 风格字符串，用于生成工具参数声明）：
     * string / integer / long / number / boolean / array / object
     */
    private String type;

    /** 描述 */
    private String description;

    /** 将类型字符串解析为 Java Class（供生成 JSON Schema 用），未知类型按 string 处理 */
    public static Class<?> resolveTypeClass(String type) {
        if (type == null) return String.class;
        switch (type) {
            case "integer": return Integer.class;
            case "long": return Long.class;
            case "number": return Double.class;
            case "boolean": return Boolean.class;
            case "array": return java.util.List.class;
            case "object": return java.util.Map.class;
            default: return String.class;
        }
    }

    /** 解析当前参数类型为 Java Class */
    public Class<?> typeClass() {
        return resolveTypeClass(type);
    }
}
