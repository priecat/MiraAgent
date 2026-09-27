package net.itzq.mira.modules.config;

import lombok.Builder;
import lombok.Data;

/**
 * 应用级设置声明（编排运行时协议 · {@code declaration.appSettingsSchema}）。
 *
 * <p>与 {@code PluginSettingDef} 同构但归属不同：插件设置由**插件 manifest** 声明，
 * 应用级设置由**宿主**声明（启动装配时播种进内核声明）。值统一存
 * {@code GlobalConfigManager.appSettings}，随声明导出/导入——
 * 堵上"宿主自有设置（如 web_fetch 的 SSRF 开关）不随编排包分发"的缝隙。
 *
 * <p>消费方式与插件设置一致：执行/工具侧经
 * {@code GlobalConfigManager.config().appSetting(key, def)} 直读，不依赖宿主透传。
 */
@Data
@Builder
public class AppSettingDef {

    public static final String TYPE_STRING = "string";
    public static final String TYPE_BOOLEAN = "boolean";
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_SECRET = "secret";

    /** 设置键（全局命名空间，建议带域前缀：如 webfetch.blockPrivateNetwork） */
    private String key;

    /** 类型：string / number / boolean / secret（secret 走凭据策略，前端密码框渲染） */
    @Builder.Default
    private String type = TYPE_STRING;

    /** 显示名 */
    private String label;

    /** 说明 */
    private String description;

    /** 默认值（字符串形态；消费端按类型转换） */
    private String defaultValue;

    /** 是否必填 */
    private boolean required;

    /** 该键是否属敏感凭据（type=secret 时为 true，导出按凭据策略 mask） */
    public boolean isSecret() {
        return TYPE_SECRET.equalsIgnoreCase(type);
    }
}
