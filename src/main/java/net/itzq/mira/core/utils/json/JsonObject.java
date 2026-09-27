package net.itzq.mira.core.utils.json;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 有序 JSON 对象，平替 fastjson2 JSONObject（继承 Map 语义，put/keySet/entrySet 等直接可用）。
 * 取值方法对齐 fastjson 宽松语义：类型不符时尽量转换，取不到返回 null。
 *
 * @author tangzq
 */
public class JsonObject extends LinkedHashMap<String, Object> {

    private static final long serialVersionUID = 1L;

    public JsonObject() {
        super();
    }

    public JsonObject(Map<String, Object> map) {
        super();
        if (map != null) {
            putAll(map);
        }
    }

    public static JsonObject parse(String json) {
        return JsonUtil.parseObject(json);
    }

    public static JsonObject parseObject(String json) {
        return JsonUtil.parseObject(json);
    }

    public static <T> T parseObject(String json, Class<T> clazz) {
        return JsonUtil.parseObject(json, clazz);
    }

    public static String toJSONString(Object object) {
        return JsonUtil.toJson(object);
    }

    @Override
    public Object put(String key, Object value) {
        return super.put(key, value);
    }

    public String getString(String key) {
        Object v = get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof Number || v instanceof Boolean || v instanceof Character) {
            return String.valueOf(v);
        }
        return JsonUtil.toJson(v);
    }

    public JsonObject getJSONObject(String key) {
        Object v = get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof JsonObject) {
            return (JsonObject) v;
        }
        if (v instanceof Map) {
            return new JsonObject((Map<String, Object>) v);
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.isEmpty()) {
                return null;
            }
            return JsonUtil.parseObject(s);
        }
        return JsonUtil.parseObject(JsonUtil.toJson(v));
    }

    /** 别名，等价 {@link #getJSONObject(String)} */
    public JsonObject getJsonObject(String key) {
        return getJSONObject(key);
    }

    /** 链式写入（平替 fastjson fluentPut） */
    public JsonObject fluentPut(String key, Object value) {
        put(key, value);
        return this;
    }

    /** 平替 fastjson getIntValue：缺失或无法转换时返回 0 */
    public int getIntValue(String key) {
        Integer v = TypeConvert.toInteger(get(key));
        return v == null ? 0 : v;
    }

    /** 平替 fastjson getLongValue：缺失或无法转换时返回 0 */
    public long getLongValue(String key) {
        Long v = TypeConvert.toLong(get(key));
        return v == null ? 0L : v;
    }

    /** 平替 fastjson getBooleanValue：缺失或无法转换时返回 false */
    public boolean getBooleanValue(String key) {
        Boolean v = TypeConvert.toBoolean(get(key));
        return v != null && v;
    }

    public JsonArray getJSONArray(String key) {
        Object v = get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof JsonArray) {
            return (JsonArray) v;
        }
        if (v instanceof Collection) {
            JsonArray arr = new JsonArray();
            for (Object item : (Collection<?>) v) {
                arr.add(JsonUtil.wrap(item));
            }
            return arr;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.isEmpty()) {
                return null;
            }
            return JsonUtil.parseArray(s);
        }
        return JsonUtil.parseArray(JsonUtil.toJson(v));
    }

    /** 别名，等价 {@link #getJSONArray(String)} */
    public JsonArray getJsonArray(String key) {
        return getJSONArray(key);
    }

    public Integer getInteger(String key) {
        return TypeConvert.toInteger(get(key));
    }

    public Long getLong(String key) {
        return TypeConvert.toLong(get(key));
    }

    public Boolean getBoolean(String key) {
        return TypeConvert.toBoolean(get(key));
    }

    public Double getDouble(String key) {
        return TypeConvert.toDouble(get(key));
    }

    public Float getFloat(String key) {
        return TypeConvert.toFloat(get(key));
    }

    public BigDecimal getBigDecimal(String key) {
        return TypeConvert.toBigDecimal(get(key));
    }

    public Date getDate(String key) {
        return TypeConvert.toDate(get(key));
    }

    /**
     * 按指定类型取值，平替 fastjson JSONObject.getObject(key, clazz)
     */
    @SuppressWarnings("unchecked")
    public <T> T getObject(String key, Class<T> clazz) {
        Object v = get(key);
        if (v == null) {
            return null;
        }
        if (clazz.isInstance(v)) {
            return (T) v;
        }
        String json = JsonUtil.toJson(v);
        return JsonUtil.parseObject(json, clazz);
    }

    public String toJSONString() {
        return JsonUtil.toJson(this);
    }

    /**
     * 转为指定 Bean（平替 fastjson JSONObject.to(clazz)）
     */
    public <T> T to(Class<T> clazz) {
        return JsonUtil.parseObject(JsonUtil.toJson(this), clazz);
    }
}
