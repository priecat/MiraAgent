package net.itzq.mira.modules.config;

import lombok.Data;

/**
 * 提示词域（编排运行时协议 · declaration.prompts）。
 *
 * <p>内核默认资产：基线系统提示词从 {@code core:prompt/agent_system.md} 装载；
 * 宿主可覆盖（编辑进内核 → 随快照导出）。
 */
@Data
public class PromptConfig {

    /** 基线系统提示词全文（为空时由内核按 source 装载） */
    private String baseSystem;

    /** 来源标识：{@code core:prompt/agent_system.md} / {@code inline} */
    private String source = DEFAULT_SOURCE;

    public static final String DEFAULT_SOURCE = "core:prompt/agent_system.md";

    /** 当前值：为空则从 source 装载，装载失败返回空串 */
    public String resolve() {
        if (baseSystem != null && !baseSystem.trim().isEmpty()) {
            return baseSystem;
        }
        if (source != null && source.startsWith("core:")) {
            String path = source.substring("core:".length());
            try {
                String text = net.itzq.mira.core.utils.PromptLoader.readFileString(path);
                return text == null ? "" : text;
            } catch (Exception e) {
                return "";
            }
        }
        return "";
    }
}
