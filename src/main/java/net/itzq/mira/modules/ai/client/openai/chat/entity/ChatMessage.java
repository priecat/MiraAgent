package net.itzq.mira.modules.ai.client.openai.chat.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import net.itzq.mira.modules.ai.client.openai.chat.enums.ChatMessageType;
import net.itzq.mira.modules.ai.client.openai.tool.ToolCall;

import java.util.List;

/**
 * ChatMessage
 */
@Data
@Builder
// protected（而非 private）：允许持久化包装子类（PersistedMessage）继承——
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {
    private Content content;
    private String role;
    private String name;
    private String refusal;

    @JsonProperty("reasoning_content")
    private String reasoningContent;

    @JsonProperty("tool_call_id")
    private String toolCallId;

    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    public ChatMessage(String userMessage) {
        this.role = ChatMessageType.USER.getRole();
        this.content = Content.ofText(userMessage);
    }
    public ChatMessage(ChatMessageType role, String message) {
        this.role = role.getRole();
        this.content = Content.ofText(message);
    }
    public ChatMessage(String role, String message) {
        this.role = role;
        this.content = Content.ofText(message);
    }

    public static ChatMessage withSystem(String content) {
        return new ChatMessage(ChatMessageType.SYSTEM, content);
    }

    public static ChatMessage withUser(String content) {
        return new ChatMessage(ChatMessageType.USER, content);
    }
    public static ChatMessage withUser(String content, String ...images) {
        return ChatMessage.builder()
                .role(ChatMessageType.USER.getRole())
                .content(Content.ofMultiModals(Content.MultiModal.withMultiModal(content, images)))
                .build();
    }

    public static ChatMessage withAssistant(String content) {
        return new ChatMessage(ChatMessageType.ASSISTANT, content);
    }
    public static ChatMessage withAssistant(List<ToolCall> toolCalls) {
        return ChatMessage.builder()
                .role(ChatMessageType.ASSISTANT.getRole())
                .toolCalls(toolCalls)
                .build();
    }

    public static ChatMessage withTool(String content, String toolCallId) {
        return ChatMessage.builder()
                .role(ChatMessageType.TOOL.getRole())
                .content(Content.ofText(content))
                .toolCallId(toolCallId)
                .build();
    }





}
