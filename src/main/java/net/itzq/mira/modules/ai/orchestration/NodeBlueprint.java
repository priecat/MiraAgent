package net.itzq.mira.modules.ai.orchestration;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点执行单元声明（编排运行时协议 · workflow.nodes[].blueprint）。
 *
 * <p><b>blueprint 是绑定层</b>：既不属声明（declaration）也不属调用（invocation）——
 * 它按名引用声明、按名消费调用：
 * <ul>
 *   <li>{@code refs}：按名引用声明（model alias / toolkit id / …），值可为
 *       {@code @invocation.<key>} 形态做运行时间接引用</li>
 *   <li>{@code requires}：执行必须的调用键（供 validate 出"缺什么"清单）</li>
 *   <li>{@code config}：节点私有配置（现 node_data 形状，语义升级为 blueprint.config）</li>
 * </ul>
 */
@Data
public class NodeBlueprint {

    /** 节点类型（manifest.nodeType，如 ai-chat-node） */
    private String type;

    /** 执行必须的调用键（如 ["invocation.question"] 或 ["question"]） */
    private List<String> requires = new ArrayList<>();

    /** 按名引用声明：refName → 引用值（或 @invocation.key） */
    private Map<String, Object> refs = new LinkedHashMap<>();

    /** 节点私有配置（= 原 node_data） */
    private Map<String, Object> config = new LinkedHashMap<>();

    /**
     * 由 node_data + manifest 的 refs 元数据装配 blueprint。
     *
     * @param type         节点类型
     * @param nodeData     节点私有配置（原 node_data）
     * @param requires     协议 requires 元数据（manifest 声明）
     * @param refKeyMap    refs 元数据：refName → nodeData 里的键名
     *                     （如 {"model":"model_id","toolkits":"toolkits"}）
     */
    public static NodeBlueprint from(String type, Map<String, Object> nodeData,
                                     List<String> requires, Map<String, String> refKeyMap) {
        NodeBlueprint bp = new NodeBlueprint();
        bp.setType(type);
        bp.setConfig(nodeData == null ? new LinkedHashMap<String, Object>() : nodeData);
        if (requires != null) {
            bp.setRequires(new ArrayList<String>(requires));
        }
        if (refKeyMap != null) {
            for (Map.Entry<String, String> e : refKeyMap.entrySet()) {
                Object v = nodeData == null ? null : nodeData.get(e.getValue());
                if (v != null) {
                    bp.getRefs().put(e.getKey(), v);
                }
            }
        }
        return bp;
    }
}
