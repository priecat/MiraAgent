package net.itzq.mira.core.utils;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.core.utils.json.JsonUtil;
import net.itzq.mira.core.utils.json.TypeConvert;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * @description 接收参数工具类
 * @author tangzq
 * @created 2019/3/7 19:26
 */
@Slf4j
public class PropsMap implements Map<String, Object>, Serializable {
    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_INITIAL_CAPACITY = 16;
    private final Map<String, Object> params;
    private static final Map<String, Object> temp = new HashMap<>();

    public PropsMap() {
        this(false);
    }

    public PropsMap(Map<String, Object> params) {
        this.params = params;
    }

    public PropsMap(boolean ordered) {
        if (ordered) {
            this.params = new LinkedHashMap(DEFAULT_INITIAL_CAPACITY);
        } else {
            this.params = new HashMap(DEFAULT_INITIAL_CAPACITY);
        }
    }

    // 获取Key值
    public String getString(String key) {
        Object s = this.get(key);
        return s != null ? s.toString() : null;
    }

    public String getStr(String key) {
        return this.getString(key);
    }

    // 获取Key值
    public String getStringValue(String key, String defVal) {
        Object s = this.get(key);
        return s != null ? s.toString() : defVal;
    }

    // key值 不存在
    public boolean isNullKey(String key) {
        Object ot = this.get(key);
        return ot == null;
    }

    // Key值 不存在 或 长度为0
    public boolean isEmptyKey(String key) {
        return isNullKey(key) ? true : this.getString(key).length() == 0;
    }

    // Map To JsonString
    public String toJson() {
        return JsonUtil.toJson(this.params);
    }

    // Map To Bean
    public <T> T toBean(Class<T> clazz) {
        return JsonUtil.parseObject(toJson(), clazz);
    }

    //  key names
    public String[] getKeyNames() {
        Set<String> attrNameSet = getSrcMap().keySet();
        return attrNameSet.toArray(new String[attrNameSet.size()]);
    }

    //  key values
    public Object[] getKeyValues() {
        Collection<Object> attrValueCollection = getSrcMap().values();
        return attrValueCollection.toArray(new Object[attrValueCollection.size()]);
    }

    // 取某几个key值
    public PropsMap toMap(String... keys) {
        PropsMap rtnMap = new PropsMap(true);
        for (String key : keys) {
            if (this.params.containsKey(key)) {
                rtnMap.put(key, this.params.get(key));
            }
        }
        return rtnMap;
    }

    // 参数检查
    public String valid(String... keys) {
        return valid(null, null, true, keys);
    }

    public String valid(boolean emptyTrueNullFalse, String... keys) {
        return valid(null, null, emptyTrueNullFalse, keys);
    }

    private String valid(String msgStart, String msgEnd, boolean emptyTrueNullFalse, String... keys) {
        String msgs = "参数：";
        String msge = " 不存在或为无效值";
        StringBuilder msg = new StringBuilder();
        if (msgStart != null && msgStart.length() > 0) {
            msgs = msgStart;
        }
        if (msgEnd != null && msgEnd.length() > 0) {
            msge = msgEnd;
        }
        for (String s : keys) {
            boolean isInvalid = emptyTrueNullFalse ? isEmptyKey(s) : isNullKey(s);
            if (isInvalid) {
                msg.append("[").append(s).append("]").append(",");
            }
        }
        if (msg.length() > 0) {
            msg.deleteCharAt(msg.length() - 1);
            msg.append(msge);
            return msgs + msg.toString();
        } else {
            return null;
        }
    }

//    public PropsMap underlineToCamel() {
//        Map newMap = new LinkedHashMap(DEFAULT_INITIAL_CAPACITY);
//        for (Object o : this.keySet()) {
//            if (o instanceof String) {
//                String newKey = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, ((String) o).toLowerCase());
//                newMap.put(newKey, this.get(o));
//            } else {
//                newMap.put(o, this.get(o));
//            }
//        }
//        return new PropsMap(newMap);
//    }

    @Override
    public int size() {
        return this.params.size();
    }

    @Override
    public boolean isEmpty() {
        return this.params.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return this.params.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return this.params.containsValue(value);
    }

    @Override
    public Object get(Object key) {
        return this.params.get(key);
    }

    @Override
    public Object put(String key, Object value) {
        return this.params.put(key, value);
    }

    @Override
    public Object remove(Object key) {
        return this.params.remove(key);
    }

    @Override
    public void putAll(Map<? extends String, ?> m) {
        this.params.putAll(m);
    }

    @Override
    public void clear() {
        this.params.clear();
    }

    @Override
    public Set<String> keySet() {
        return this.params.keySet();
    }

    @Override
    public Collection<Object> values() {
        return this.params.values();
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return this.params.entrySet();
    }

    @Override
    public int hashCode() {
        return this.params.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PropsMap))
            return false;
        if (o == this)
            return true;
        return this.getSrcMap().equals(((PropsMap) o).getSrcMap());
    }

    public Map<String, Object> getSrcMap() {
        return this.params;
    }

    public String printf() {
        if (params == null) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Entry<String, Object> e : getSrcMap().entrySet()) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            Object value = e.getValue();
            if (value != null) {
                value = value.toString();
            }
            sb.append(e.getKey()).append(':').append(value);
        }
        sb.append('}');
        return sb.toString();
    }

    // 宽松类型转换（原 com.alibaba.fastjson.util.TypeUtils 语义）
    public Boolean getBoolean(String key) {
        Object value = this.get(key);
        return value == null ? null : TypeConvert.toBoolean(value);
    }

    public boolean getBooleanValue(String key, boolean defaultVal) {
        Object value = this.get(key);
        Boolean booleanVal = TypeConvert.toBoolean(value);
        return booleanVal == null ? defaultVal : booleanVal;
    }

    public Integer getInteger(String key) {
        Object value = this.get(key);
        return TypeConvert.toInteger(value);
    }

    public int getIntValue(String key, int defaultVal) {
        Object value = this.get(key);
        Integer intVal = TypeConvert.toInteger(value);
        return intVal == null ? defaultVal : intVal;
    }

    public Long getLong(String key) {
        Object value = this.get(key);
        return TypeConvert.toLong(value);
    }

    public long getLongValue(String key, long defaultVal) {
        Object value = this.get(key);
        Long longVal = TypeConvert.toLong(value);
        return longVal == null ? defaultVal : longVal;
    }

    public Float getFloat(String key) {
        Object value = this.get(key);
        return TypeConvert.toFloat(value);
    }

    public float getFloatValue(String key, float defaultVal) {
        Object value = this.get(key);
        Float floatValue = TypeConvert.toFloat(value);
        return floatValue == null ? defaultVal : floatValue;
    }

    public Double getDouble(String key) {
        Object value = this.get(key);
        return TypeConvert.toDouble(value);
    }

    public double getDoubleValue(String key, double defaultVal) {
        Object value = this.get(key);
        Double doubleValue = TypeConvert.toDouble(value);
        return doubleValue == null ? defaultVal : doubleValue;
    }

    public BigDecimal getBigDecimal(String key) {
        Object value = this.get(key);
        return TypeConvert.toBigDecimal(value);
    }

    public BigInteger getBigInteger(String key) {
        Object value = this.get(key);
        return TypeConvert.toBigInteger(value);
    }

    public Date getDate(String key) {
        Object value = this.get(key);
        return TypeConvert.toDate(value);
    }

    // 2019-01-01 01:01:01 + yyyy-MM-dd HH:mm:ss -> Date
    public Date yyyyMMddHHmmssToDate(String key, String format) {
        if (isNullKey(key)) {
            return null;
        }
        Date n = null;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(format);
            n = sdf.parse(getString(key));
        } catch (Exception e) {
            log.error("getDate Error", e);
        }
        return n;
    }

    // 2019-01-01 01:01:01 + yyyy-MM-dd HH:mm:ss -> Date
    public Date timestampStrToDate(String key) {
        if (isNullKey(key)) {
            return null;
        }
        Date n = null;
        try {
            String ts = getString(key);
            // 秒->毫秒
            if (ts.length() == 10) {
                ts = ts + "000";
            }
            n = new Date(Long.parseLong(ts));
        } catch (Exception e) {
            log.error("getDate Error", e);
        }
        return n;
    }
}
