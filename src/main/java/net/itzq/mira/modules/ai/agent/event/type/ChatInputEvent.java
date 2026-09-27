package net.itzq.mira.modules.ai.agent.event.type;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天输入事件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatInputEvent extends BaseEvent {
    private String question;

    /** 随消息附带的图片（base64 data URI 或 URL）；可为 null */
    private List<String> images;
}
