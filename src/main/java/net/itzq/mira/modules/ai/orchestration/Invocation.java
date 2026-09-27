package net.itzq.mira.modules.ai.orchestration;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;
import net.itzq.mira.core.utils.json.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用参数（编排运行时协议 · invocation.json）。
 *
 * <p>语义："这一次怎么跑"。由宿主在发送时组装，**即用即弃**——任何宿主不得把它
 * 写进声明（declaration）或编排（workflow）文件。独立运行时（工作流执行器）也可手工构造。
 *
 * <p>字段定位：
 * <ul>
 *   <li>question / images / workspacePath —— 用户输入与环境绑定</li>
 *   <li>modelAlias / enableThinking —— 调用级覆盖（缺省走声明里的默认值）</li>
 *   <li>historyId —— 会话引用；缺省 = 不挂持久化 = <b>即用即释放</b></li>
 *   <li>extra —— 宿主私有扩展键（chatMode / tools 等），不参与协议校验</li>
 * </ul>
 */
@Data
@Builder
public class Invocation {

    /** 用户问题（必填；节点 blueprint 的 requires 通常含此项） */
    private String question;

    /** 图片附件（base64 data URI 列表，可空） */
    @Singular("image")
    private List<String> images;

    /** 会话工作空间路径（真实磁盘路径，可空） */
    private String workspacePath;

    /** 调用级模型覆盖（可空 → 用声明 defaultModel） */
    private String modelAlias;

    /** 调用级思考开关覆盖（可空 → 用声明默认） */
    private Boolean enableThinking;

    /** 会话 id（可空 = 不挂持久化，即用即释放） */
    private String historyId;

    /** 宿主私有扩展键（不参与协议校验） */
    @Singular("extra")
    private Map<String, Object> extra;

    /** 按键取值（含扩展键）；不存在返回 null */
    public Object get(String key) {
        if (key == null) {
            return null;
        }
        if ("question".equals(key)) {
            return question;
        }
        if ("images".equals(key)) {
            return images;
        }
        if ("workspacePath".equals(key)) {
            return workspacePath;
        }
        if ("modelAlias".equals(key)) {
            return modelAlias;
        }
        if ("enableThinking".equals(key)) {
            return enableThinking;
        }
        if ("historyId".equals(key)) {
            return historyId;
        }
        return extra == null ? null : extra.get(key);
    }

    /** 某键是否有可用值（null / 空串 / 空集合视为无） */
    public boolean has(String key) {
        Object v = get(key);
        if (v == null) {
            return false;
        }
        if (v instanceof CharSequence) {
            return ((CharSequence) v).length() > 0;
        }
        if (v instanceof java.util.Collection) {
            return !((java.util.Collection<?>) v).isEmpty();
        }
        return true;
    }

    /** 序列化为协议形态（进 chatParams / 可落盘） */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("question", question);
        m.put("images", images == null ? new ArrayList<String>() : images);
        m.put("workspacePath", workspacePath);
        m.put("modelAlias", modelAlias);
        m.put("enableThinking", enableThinking);
        m.put("historyId", historyId);
        if (extra != null && !extra.isEmpty()) {
            m.put("extra", extra);
        }
        return m;
    }

    /** 从协议形态还原（插件执行器/独立运行时入口） */
    @SuppressWarnings("unchecked")
    public static Invocation fromMap(Map<String, Object> m) {
        if (m == null) {
            return Invocation.builder().build();
        }
        InvocationBuilder b = Invocation.builder()
                .question(str(m.get("question")))
                .workspacePath(str(m.get("workspacePath")))
                .modelAlias(str(m.get("modelAlias")))
                .historyId(str(m.get("historyId")));
        Object thinking = m.get("enableThinking");
        if (thinking instanceof Boolean) {
            b.enableThinking((Boolean) thinking);
        } else if (thinking != null && !String.valueOf(thinking).trim().isEmpty()) {
            b.enableThinking(Boolean.parseBoolean(String.valueOf(thinking).trim()));
        }
        Object imgs = m.get("images");
        if (imgs instanceof java.util.Collection) {
            for (Object o : (java.util.Collection<?>) imgs) {
                if (o != null) {
                    b.image(String.valueOf(o));
                }
            }
        }
        Object extra = m.get("extra");
        if (extra instanceof Map) {
            b.extra((Map<String, Object>) extra);
        }
        return b.build();
    }

    /** 宽松还原（前端 JSON 字符串或 Map 都接受） */
    public static Invocation fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Invocation.builder().build();
        }
        try {
            return fromMap(JsonUtil.parseObject(json));
        } catch (Exception e) {
            return Invocation.builder().build();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
