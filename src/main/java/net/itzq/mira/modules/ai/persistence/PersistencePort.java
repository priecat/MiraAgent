package net.itzq.mira.modules.ai.persistence;

import net.itzq.mira.modules.ai.client.openai.chat.entity.ChatMessage;
import net.itzq.mira.modules.ai.entity.chat.AnsResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 持久化端口（编排运行时协议 · PersistencePort SPI）。
 *
 * <p>内核定义、宿主实现：内核只认端口，不认识任何存储实现（SQLite/内存/远端）。
 * <b>默认 {@link #NOOP} 即"即用即释放"</b>——不挂持久化也能完整跑编排，
 * 历史/事件随用完即弃；宿主实现它即可获得记忆、回放与历史面板能力。
 *
 * <p>普通对话（KernelChatEngine）与工作流节点执行器共用同一端口实现，
 * 在 core 眼里二者是平等的两种执行形态。
 */
public interface PersistencePort {

    /** 装载会话完整上下文（时间正序；可含未闭合轮次的占位修复） */
    List<ChatMessage> loadContext(String historyId);

    /**
     * 装载会话上下文（可控是否保留未闭合轮次）。
     *
     * @param preserveUnclosedToolCalls true 时保留尾部 assistant.tool_calls（断点续行用），
     *                                  不补"被中断"占位
     */
    default List<ChatMessage> loadContext(String historyId, boolean preserveUnclosedToolCalls) {
        return loadContext(historyId);
    }

    /** 持久化会话消息（与普通对话同一张上下文表；historyId 为节点会话 id 时即节点历史） */
    void persistContext(String historyId, List<ChatMessage> messages);

    /** 逐条持久化事件（与普通对话同一张事件表，供该 historyId 的回放） */
    void appendEvent(String historyId, AnsResponse event);

    /**
     * 确保会话历史行存在（历史面板可见；已存在则不动）。
     *
     * @param mode 会话模式标识（{@code chat} / 其他），由宿主决定其展示分组语义
     */
    void ensureSession(String historyId, String title, String mode);

    /** 是否启用持久化（NOOP 返回 false）——执行前可据此跳过装载/落库链路 */
    default boolean isEnabled() {
        return true;
    }

    /** 即用即释放：不持久化、无记忆，跑完即弃 */
    PersistencePort NOOP = new PersistencePort() {
        @Override
        public List<ChatMessage> loadContext(String historyId) {
            return new ArrayList<>();
        }

        @Override
        public void persistContext(String historyId, List<ChatMessage> messages) {
            // no-op
        }

        @Override
        public void appendEvent(String historyId, AnsResponse event) {
            // no-op
        }

        @Override
        public void ensureSession(String historyId, String title, String mode) {
            // no-op
        }

        @Override
        public boolean isEnabled() {
            return false;
        }
    };
}
