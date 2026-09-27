package net.itzq.mira.modules.config;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件（工具包/节点插件）设置声明值（编排运行时协议 · declaration.plugins）。
 *
 * <p>插件在 manifest 声明 {@code settingsSchema}，宿主通用渲染器出 UI，
 * 值落宿主持久化（{@code plugin.<pluginId>.<key>}），导出进声明快照。
 */
@Data
public class PluginSettings {

    /** 插件版本（manifest.version，便于导入端兼容判断） */
    private String version;

    /** 设置键值（secret 类型已按 CredentialPolicy 处理） */
    private Map<String, Object> settings = new LinkedHashMap<>();
}
