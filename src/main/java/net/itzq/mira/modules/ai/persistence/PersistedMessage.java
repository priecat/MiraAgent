package net.itzq.mira.modules.ai.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import net.itzq.mira.modules.ai.client.openai.chat.entity.ChatMessage;

/**
 * 持久化消息包装：{@link ChatMessage} 的子类，仅在持久化队列（落库边界）中携带锚点 id。
 *
 * <p>设计约束：
 * <ul>
 *   <li><b>不进 history</b>——history（OpenAI 请求序列化来源）永远只存纯 {@link ChatMessage}，
 *       本类实例只进入持久化队列，因此 {@code id} 绝不会出现在请求体里；</li>
 *   <li><b>对既有实现零影响</b>——{@code HistoryPersist} 实现按运行时类型序列化（JsonMapper），
 *       {@code id} 为 null 时（admin 版等不产生锚点的工程）输出与纯 ChatMessage 完全一致；</li>
 *   <li><b>行内形态</b>——落库 JSON 为顶层平铺：{@code {"id":..,"role":..,"content":..,...}}，
 *       读回时 {@code ChatMessage} 的 {@code ignoreUnknown} 会忽略未知 id 字段。</li>
 * </ul>
 *
 * <p>{@code id} 语义：轮次锚点（fork/压缩按它与 UserInput 事件 info.msgId 同源关联定位，
 * 不依赖两侧轮次索引对齐），仅 user 消息携带。
 */
@Getter
@Setter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PersistedMessage extends ChatMessage {

    /** 轮次锚点 id（仅 user 消息携带）；null 表示不携带（与旧形态序列化一致） */
    private String id;

    public PersistedMessage() {
    }

    /**
     * 由既有消息构造持久化副本（逐字段拷贝，确定性、无序列化依赖）。
     *
     * @param persistId 轮次锚点 id，可为 null
     */
    public static PersistedMessage of(ChatMessage message, String persistId) {
        PersistedMessage pm = new PersistedMessage();
        pm.setContent(message.getContent());
        pm.setRole(message.getRole());
        pm.setName(message.getName());
        pm.setRefusal(message.getRefusal());
        pm.setReasoningContent(message.getReasoningContent());
        pm.setToolCallId(message.getToolCallId());
        pm.setToolCalls(message.getToolCalls());
        pm.setId(persistId);
        return pm;
    }
}
