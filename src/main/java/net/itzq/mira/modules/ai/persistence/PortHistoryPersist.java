package net.itzq.mira.modules.ai.persistence;

import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.openai.chat.entity.ChatMessage;
import net.itzq.mira.modules.ai.entity.chat.AnsResponse;

import java.util.List;

/**
 * 把 {@link PersistencePort} 适配成内核既有的 {@link AbstractHistoryPersist} 回调。
 *
 * <p>内核 agent 循环的落库回调是 {@code AbstractHistoryPersist}；本适配器把回调
 * 转发给端口——宿主只需实现端口，普通对话与工作流节点自动获得一致的持久化行为。
 */
public class PortHistoryPersist extends AbstractHistoryPersist {

    private final PersistencePort port;
    private final String historyId;
    private final boolean persistEvents;

    private PortHistoryPersist(PersistencePort port, String historyId, boolean persistEvents) {
        this.port = port;
        this.historyId = historyId;
        this.persistEvents = persistEvents;
    }

    /** 端口为空时退化为 NOOP（即用即释放）；事件一并经端口落库 */
    public static PortHistoryPersist of(PersistencePort port, String historyId) {
        return of(port, historyId, true);
    }

    /**
     * @param persistEvents 是否经端口落事件。宿主已在事件管道层落库时传 false
     *                      （否则同一事件落两遍）。
     */
    public static PortHistoryPersist of(PersistencePort port, String historyId, boolean persistEvents) {
        return new PortHistoryPersist(port == null ? PersistencePort.NOOP : port, historyId, persistEvents);
    }

    @Override
    public void saveChatMessages(List<ChatMessage> chatMessages, AgentContextHolder context) {
        port.persistContext(historyId, chatMessages);
    }

    @Override
    public void saveEventMessages(List<?> eventMessages) {
        if (!persistEvents || eventMessages == null) {
            return;
        }
        for (Object o : eventMessages) {
            if (o instanceof AnsResponse) {
                port.appendEvent(historyId, (AnsResponse) o);
            }
        }
    }
}
