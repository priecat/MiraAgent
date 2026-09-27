package net.itzq.mira.core.utils.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * JSON 数组，平替 fastjson2 JSONArray（继承 List 语义，add/get 等直接可用）。
 * 取值方法对齐 fastjson 宽松语义：类型不符时尽量转换，越界或取不到返回 null。
 *
 * @author tangzq
 */
public class JsonArray extends ArrayList<Object> {

    private static final long serialVersionUID = 1L;

    public JsonArray() {
        super();
    }

    public JsonArray(Collection<?> collection) {
        super();
        if (collection != null) {
            for (Object item : collection) {
                add(JsonUtil.wrap(item));
            }
        }
    }

    public static JsonArray parse(String json) {
        return JsonUtil.parseArray(json);
    }

    public static String toJSONString(Object object) {
        return JsonUtil.toJson(object);
    }

    private <T> T getOrNull(int index) {
        if (index < 0 || index >= size()) {
            return null;
        }
        return (T) get(index);
    }

    public String getString(int index) {
        Object v = getOrNull(index);
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

    public JsonObject getJSONObject(int index) {
        Object v = getOrNull(index);
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

    /** 别名，等价 {@link #getJSONObject(int)} */
    public JsonObject getJsonObject(int index) {
        return getJSONObject(index);
    }

    /** 别名，等价 {@link #getJSONArray(int)} */
    public JsonArray getJsonArray(int index) {
        return getJSONArray(index);
    }

    public Float getFloat(int index) {
        return TypeConvert.toFloat(getOrNull(index));
    }

    public JsonArray getJSONArray(int index) {
        Object v = getOrNull(index);
        if (v == null) {
            return null;
        }
        if (v instanceof JsonArray) {
            return (JsonArray) v;
        }
        if (v instanceof Collection) {
            return new JsonArray((Collection<?>) v);
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

    public Integer getInteger(int index) {
        return TypeConvert.toInteger(getOrNull(index));
    }

    public Long getLong(int index) {
        return TypeConvert.toLong(getOrNull(index));
    }

    public Boolean getBoolean(int index) {
        return TypeConvert.toBoolean(getOrNull(index));
    }

    public Double getDouble(int index) {
        return TypeConvert.toDouble(getOrNull(index));
    }

    public String toJSONString() {
        return JsonUtil.toJson(this);
    }

    /**
     * 转为指定类型 List（平替 fastjson JSONArray.toJavaList(clazz)）
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> toJavaList(Class<T> clazz) {
        List<T> list = new ArrayList<>();
        for (Object item : this) {
            if (item == null) {
                list.add(null);
            } else if (clazz.isInstance(item)) {
                list.add((T) item);
            } else {
                list.add(JsonUtil.parseObject(JsonUtil.toJson(item), clazz));
            }
        }
        return list;
    }
}
