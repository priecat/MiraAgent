package net.itzq.mira.core.utils.json;

import java.util.List;
import java.util.Map;

/**
 * 轻量 JSONPath 求值器，平替 fastjson2 JSONPath 的常用路径语法。
 * <p>
 * 支持：{@code $.a.b[0].c}、{@code $.list[1]}、{@code $}（根）、数组末尾负索引不支持的 fastjson 同样语义按 null 处理。
 * 缺失路径返回 null，不抛异常。
 *
 * @author tangzq
 */
public class JsonPathEval {

    private JsonPathEval() {
    }

    /**
     * 对 JSON 字符串或 Map/List/JsonObject/JsonArray 求值
     *
     * @param json JSON 字符串，或已解析的 Map/List 结构
     * @param path 形如 $.a.b[0].c 的路径
     * @return 命中值（可能是 JsonObject/JsonArray/基础类型），未命中返回 null
     */
    public static Object eval(Object json, String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        Object root;
        if (json instanceof String) {
            String s = ((String) json).trim();
            if (s.isEmpty()) {
                return null;
            }
            root = JsonUtil.parse(s);
        } else {
            root = json;
        }
        if (root == null) {
            return null;
        }
        String normalized = path.trim();
        if ("$".equals(normalized) || "$.".equals(normalized)) {
            return root;
        }
        if (normalized.startsWith("$.")) {
            normalized = normalized.substring(2);
        } else if (normalized.startsWith("$")) {
            normalized = normalized.substring(1);
        }
        Object current = root;
        for (String segment : normalized.split("\\.")) {
            if (segment.isEmpty()) {
                continue;
            }
            // 处理形如 key[0][1] 的段
            String key = segment;
            Integer index = null;
            int bracket = key.indexOf('[');
            if (bracket >= 0) {
                String idxPart = key.substring(bracket).trim();
                if (!idxPart.matches("^\\[\\d+](\\[\\d+])*$")) {
                    return null;
                }
                key = key.substring(0, bracket);
                int first = idxPart.indexOf('[');
                index = Integer.parseInt(idxPart.substring(first + 1, idxPart.indexOf(']', first)));
                // 只取第一个索引（现有场景不存在多重索引）
            }
            if (current == null) {
                return null;
            }
            if (!key.isEmpty()) {
                if (!(current instanceof Map)) {
                    return null;
                }
                current = ((Map<?, ?>) current).get(key);
            }
            if (index != null) {
                if (!(current instanceof List)) {
                    return null;
                }
                List<?> list = (List<?>) current;
                if (index < 0 || index >= list.size()) {
                    return null;
                }
                current = list.get(index);
            }
        }
        return current;
    }

    /**
     * 求值并转 String
     */
    public static String evalString(Object json, String path) {
        Object v = eval(json, path);
        if (v == null) {
            return null;
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return JsonUtil.toJson(v);
    }

    /**
     * 求值并转 JsonObject
     */
    public static JsonObject evalObject(Object json, String path) {
        Object v = eval(json, path);
        if (v == null) {
            return null;
        }
        if (v instanceof JsonObject) {
            return (JsonObject) v;
        }
        if (v instanceof Map) {
            return new JsonObject((Map<String, Object>) v);
        }
        return null;
    }

    /**
     * 求值并转 JsonArray
     */
    public static JsonArray evalArray(Object json, String path) {
        Object v = eval(json, path);
        if (v == null) {
            return null;
        }
        if (v instanceof JsonArray) {
            return (JsonArray) v;
        }
        if (v instanceof List) {
            return new JsonArray((List<?>) v);
        }
        return null;
    }
}
