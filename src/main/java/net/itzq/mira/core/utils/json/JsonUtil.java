package net.itzq.mira.core.utils.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 基于 Jackson 的宽松 JSON 工具类，平替 fastjson2 的 JSON 静态方法。
 * <p>
 * 宽松策略：
 * <ul>
 *     <li>反序列化忽略未知字段（字段对不齐不报错）</li>
 *     <li>允许单引号、不带引号的字段名、未转义控制字符</li>
 *     <li>未知枚举值读为 null、空字符串读为 null</li>
 *     <li>空字符串/空白输入解析返回 null</li>
 * </ul>
 *
 * @author tangzq
 */
public class JsonUtil {

    private static final ObjectMapper LENIENT = createMapper(false);

    private static final ObjectMapper LENIENT_NULLS = createMapper(true);

    private JsonUtil() {
    }

    private static ObjectMapper createMapper(boolean includeNulls) {
        ObjectMapper mapper = new ObjectMapper();
        // 输出包含策略：默认忽略 null（对齐 fastjson 默认），writeNulls 变体输出 null
        mapper.setSerializationInclusion(includeNulls ? JsonInclude.Include.ALWAYS : JsonInclude.Include.NON_NULL);
        // 宽松解析：单引号、无引号字段名、未转义控制字符、注释
        mapper.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
        mapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
        mapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);
        mapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
        // 宽松反序列化：忽略未知字段、未知枚举为 null、空串为 null
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);
        mapper.configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true);
        mapper.configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, true);
        // 序列化不因空对象/无 getter 报错
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        // 日期：GMT+8，统一格式
        mapper.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        mapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
        return mapper;
    }

    /**
     * 序列化为 JSON 字符串（忽略 null 字段，对齐 fastjson 默认行为）
     */
    public static String toJson(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return LENIENT.writeValueAsString(object);
        } catch (Exception e) {
            throw new IllegalStateException("toJSONString error: " + e.getMessage(), e);
        }
    }

    /**
     * 序列化为 JSON 字符串，输出 null 字段（平替 WriteMapNullValue）
     */
    public static String writeNulls(Object object) {
        if (object == null) {
            return "null";
        }
        try {
            return LENIENT_NULLS.writeValueAsString(object);
        } catch (Exception e) {
            throw new IllegalStateException("toJSONString error: " + e.getMessage(), e);
        }
    }

    /**
     * 自由解析 JSON，返回 JsonObject / JsonArray / String / Number / Boolean / null
     * （平替 fastjson JSON.parse）
     */
    public static Object parse(String json) {
        if (json == null || json.trim().isEmpty() || "null".equals(json.trim())) {
            return null;
        }
        try {
            return wrap(LENIENT.readValue(json, Object.class));
        } catch (Exception e) {
            throw new IllegalStateException("parse error: " + e.getMessage() + ", json: " + abbreviate(json), e);
        }
    }

    /**
     * 解析为 JsonObject（Map 语义）
     */
    public static JsonObject parseObject(String json) {
        Object o = parse(json);
        if (o == null) {
            return null;
        }
        if (o instanceof JsonObject) {
            return (JsonObject) o;
        }
        throw new IllegalStateException("expect json object but got: " + o.getClass().getName());
    }

    /**
     * 解析为指定 Bean（宽松：字段对不齐忽略、类型不匹配尽量容错）
     */
    public static <T> T parseObject(String json, Class<T> clazz) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return LENIENT.readValue(json, clazz);
        } catch (Exception e) {
            throw new IllegalStateException("parseObject error: " + e.getMessage() + ", json: " + abbreviate(json), e);
        }
    }

    /**
     * 解析为 JsonArray（List 语义）
     */
    public static JsonArray parseArray(String json) {
        Object o = parse(json);
        if (o == null) {
            return null;
        }
        if (o instanceof JsonArray) {
            return (JsonArray) o;
        }
        throw new IllegalStateException("expect json array but got: " + o.getClass().getName());
    }

    /**
     * 递归把 Jackson 解析出的 Map/List 包装为 JsonObject/JsonArray
     */
    @SuppressWarnings("unchecked")
    public static Object wrap(Object o) {
        if (o instanceof Map) {
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                obj.put(e.getKey(), wrap(e.getValue()));
            }
            return obj;
        }
        if (o instanceof List) {
            JsonArray arr = new JsonArray();
            for (Object item : (List<Object>) o) {
                arr.add(wrap(item));
            }
            return arr;
        }
        return o;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
