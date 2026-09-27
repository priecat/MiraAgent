package net.itzq.mira.modules.ai.orchestration;

import lombok.Data;

import java.util.List;

/**
 * 插件设置项声明（编排运行时协议 · 插件 manifest {@code settingsSchema} 元素）。
 *
 * <p>插件在 manifest 声明设置项 → 宿主通用渲染器出 UI → 值落宿主持久化
 * （{@code plugin.<pluginId>.<key>}）→ 导出进 {@code declaration.plugins} →
 * 执行时插件经内核 {@code GlobalConfigManager.pluginSetting(pluginId, key)} 直读。
 *
 * <p><b>新增插件全程零宿主代码改动</b>是这套机制的目的。
 */
@Data
public class PluginSettingDef {

    /** 设置键（命名空间内的短名，如 llmTimeoutSeconds） */
    private String key;

    /**
     * 类型：{@code string} / {@code number} / {@code boolean} / {@code secret} / {@code enum}
     * <ul>
     *   <li>secret 走凭据策略（inline / ${ENV}），前端以密码框渲染</li>
     *   <li>enum 需配合 {@link #options}</li>
     * </ul>
     */
    private String type = "string";

    /** 显示名 */
    private String label;

    /** 说明（tooltip） */
    private String description;

    /** 默认值（字符串形态；消费端按类型转换） */
    private String defaultValue;

    /** enum 选项 */
    private List<String> options;

    /** 是否必填（跳过后执行器自行 fail-closed） */
    private boolean required = false;

    /** 输入占位提示 */
    private String placeholder;

    /** 该键是否属敏感凭据（type=secret 时自动为 true） */
    public boolean isSecret() {
        return "secret".equalsIgnoreCase(type);
    }
}
