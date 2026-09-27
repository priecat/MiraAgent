package net.itzq.mira.modules.ai.persistence;

import net.itzq.mira.core.utils.json.JsonArray;
import net.itzq.mira.core.utils.json.JsonObject;
import net.itzq.mira.core.utils.json.JsonUtil;

import java.util.*;

/**
 *  DefaultStreamChunkAggregator
 *
 *  @author tangzq
 */
public class DefaultStreamChunkAggregator {

    /**
     * 聚合流式输出的JSON数组字符串
     * @param jsonArrayStr 原始流式消息JSON数组字符串
     * @return 聚合后的JSON数组
     */
    public static JsonArray aggregate(String jsonArrayStr) {
        JsonArray originalArray = JsonUtil.parseArray(jsonArrayStr);
        // key: msgId, value: 聚合中的消息对象
        Map<String, JsonObject> aggregatedMap = new HashMap<>();
        // key: msgId, value: 首次出现索引（用于排序）
        Map<String, Integer> firstIndexMap = new HashMap<>();
        // 按原始顺序记录所有消息（String表示msgId，JsonObject表示非聚合消息）
        List<Object> orderList = new ArrayList<>();
        int index = 0; // 记录需要聚合的消息的首次出现顺序

        for (int i = 0; i < originalArray.size(); i++) {
            JsonObject item = originalArray.getJSONObject(i);
            String type = item.getString("type");
            String msgId = item.getString("msgId");

            if ("ChatContent".equals(type) || "ChatReasonContent".equals(type)) {
                // 需要聚合的消息
                if (!aggregatedMap.containsKey(msgId)) {
                    // 首次出现：复制所有字段，并初始化answer
                    JsonObject aggregated = new JsonObject();
                    for (Map.Entry<String, Object> entry : item.entrySet()) {
                        aggregated.put(entry.getKey(), entry.getValue());
                    }
                    // 确保answer字段存在
                    if (!aggregated.containsKey("answer")) {
                        aggregated.put("answer", "");
                    }
                    aggregatedMap.put(msgId, aggregated);
                    firstIndexMap.put(msgId, index);
                    orderList.add(msgId);
                    index++;
                } else {
                    // 后续出现：拼接answer字段
                    JsonObject aggregated = aggregatedMap.get(msgId);
                    String currentAnswer = item.getString("answer");
                    if (currentAnswer != null) {
                        String existingAnswer = aggregated.getString("answer");
                        aggregated.put("answer", existingAnswer + currentAnswer);
                    }
                }
            } else {
                // 非聚合消息直接加入顺序列表
                orderList.add(item);
            }
        }

        // 按顺序构建结果数组
        JsonArray resultArray = new JsonArray();
        for (Object obj : orderList) {
            if (obj instanceof String) {
                String msgId = (String) obj;
                resultArray.add(aggregatedMap.get(msgId));
            } else {
                resultArray.add(obj);
            }
        }
        return resultArray;
    }

    // 如果需要返回JSON字符串，可以添加一个重载方法
    public static String aggregateToString(String jsonArrayStr) {
        return aggregate(jsonArrayStr).toJSONString();
    }
}
