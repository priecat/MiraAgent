package net.itzq.mira.modules.ai.client.tool;

import lombok.Data;

/**
 *  AiToolParam
 *
 *  @author tangzq
 */
@Data
public class AiToolParam {
    private final String name;
    private final String description;
    private final boolean required;
    private final Class<?> type;

    private AiToolParam(String name, String description, boolean required, Class<?> type) {
        this.name = name;
        this.description = description;
        this.required = required;
        this.type = type;
    }

    public static AiToolParam of(String name, String description, Class<?> type) {
        return new AiToolParam(name, description, true, type);
    }

    public static AiToolParam optional(String name, String description, Class<?> type) {
        return new AiToolParam(name, description, false, type);
    }

    public static AiToolParam of(String name, String description, boolean required, Class<?> type) {
        return new AiToolParam(name, description, required, type);
    }

}
