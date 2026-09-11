package net.itzq.mira.modules.ai.agent.event.type;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.itzq.mira.modules.ai.client.handle.StreamEventHandler;
import net.itzq.mira.modules.ai.client.openai.tool.ToolCall;

/**
 * 工具调用结束事件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CallToolEndEvent extends BaseEvent {
    private ToolCall toolCall;
    private String toolId;
    private String endMsg;
}
