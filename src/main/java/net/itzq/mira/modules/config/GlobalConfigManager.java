package net.itzq.mira.modules.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.core.utils.JsonMapper;
import net.itzq.mira.modules.ai.client.config.ApiProviderManage;
import net.itzq.mira.modules.ai.client.config.ModelApiConfig;
import net.itzq.mira.modules.ai.client.embedding.EmbeddingProviderManage;
import net.itzq.mira.modules.ai.client.embedding.EmbeddingApiConfig;
import net.itzq.mira.modules.vfs.VFS;
import net.itzq.mira.modules.workspace.FileWorkspace;
import net.itzq.mira.modules.workspace.WorkspaceConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局配置管理器 —— 统一配置入口
 *
 * <p>持有各项子系统配置变量，对外提供 set/get、JSON 导入导出能力。</p>
 *
 * <p>用法示例：
 * <pre>
 *     // 单例访问
 *     GlobalConfigManager gcm = GlobalConfigManager.getInstance();
 *
 *     // 代码构建并赋值
 *     gcm.setAgentConfig(new AgentConfig());
 *     gcm.setSseClientSimpleConfig(SseClientConfig.builder()...build());
 *
 *     // 导出 JSON（逐项 put）
 *     String json = gcm.exportToJson();
 *
 *     // 从 JSON 导入并逐项 set 回变量
 *     gcm.importFromJson(json);
 * </pre>
 *
 * @author tangzq
 */
@Slf4j
public class GlobalConfigManager {

    @Getter
    private volatile AgentConfig agentConfig = new AgentConfig();

    @Getter
    private volatile WorkspaceConfig workspaceConfig = new WorkspaceConfig();

    @Getter
    private volatile SseClientConfig sseClientSimpleConfig = new SseClientConfig();

    @Getter
    private volatile List<ModelApiConfig> models = new ArrayList<>();

    @Getter
    private volatile List<EmbeddingApiConfig> embeddings = new ArrayList<>();

    // ==================== 声明域扩展（P1：编排运行时协议） ====================

    /** 声明 schema 版本（协议先行：结构变更必须递增） */
    public static final String SCHEMA_VERSION = "1.0.0";

    @Getter
    private volatile String schemaVersion = SCHEMA_VERSION;

    /** 提示词域：基线系统提示词（内核默认资产，宿主可覆盖） */
    @Getter
    private volatile PromptConfig prompts = new PromptConfig();

    /** 工具域：工具提供者声明（导出时从注册表快照生成，导入时据此校验） */
    @Getter
    private volatile List<ToolProviderDecl> toolProviders = new ArrayList<>();

    /** 默认值域：session.compact 等内核策略默认（app 只做 UI/apply） */
    @Getter
    private volatile Map<String, Object> defaults = new LinkedHashMap<>();

    /** 插件域：插件设置值（settingsSchema 由插件 manifest 声明） */
    @Getter
    private volatile Map<String, PluginSettings> plugins = new LinkedHashMap<>();

    /** 应用级设置声明（编排运行时协议 · declaration.appSettingsSchema）：由宿主启动播种 */
    @Getter
    private volatile List<AppSettingDef> appSettingsSchema = new ArrayList<>();

    /** 应用级设置值：宿主自有工具/功能的配置，随声明导出/导入 */
    @Getter
    private volatile Map<String, Object> appSettings = new LinkedHashMap<>();

    /** 替换应用级设置声明（宿主启动装配用） */
    public void setAppSettingsSchema(List<AppSettingDef> list) {
        this.appSettingsSchema = list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    /** 写单个应用级设置值（声明内直存，随声明导出/导入） */
    public void setAppSetting(String key, Object value) {
        if (key != null && !key.trim().isEmpty()) {
            this.appSettings.put(key, value);
        }
    }

    /** 读应用级设置值；未配置返回 def */
    public Object appSetting(String key, Object def) {
        Object v = appSettings.get(key);
        return v == null ? def : v;
    }

    /**
     * 实例构造（P5 实例化）。
     *
     * <p>不再有"单例"语义：每个内核运行时（KernelRuntime）持有一个独立实例，
     * 同进程多实例的声明域互不可见。<b>兼容期</b>：静态 {@link #config()} 委托默认运行时
     * 的实例，存量调用点零改动。
     */
    public GlobalConfigManager() {
    }

    /**
     * 所属内核运行时（P5）：由 {@link net.itzq.mira.modules.runtime.KernelRuntime} 构造后注入。
     * 未注入（独立使用/测试）时，配置应用回落静态 facade（等价于默认运行时）。
     */
    private volatile net.itzq.mira.modules.runtime.KernelRuntime boundRuntime;

    /** 绑定所属运行时（KernelRuntime 构造时调用一次） */
    public void bindRuntime(net.itzq.mira.modules.runtime.KernelRuntime runtime) {
        this.boundRuntime = runtime;
    }

    /** 所属运行时（未绑定返回 null） */
    public net.itzq.mira.modules.runtime.KernelRuntime runtime() {
        return boundRuntime;
    }

    // ==================== 兼容 facade（委托默认运行时） ====================

    /**
     * @deprecated P5：改用 {@code holder.getRuntime().declaration()}；本静态入口委托
     *            默认运行时的声明实例，兼容期内保留。
     */
    @Deprecated
    public static GlobalConfigManager config() {
        return net.itzq.mira.modules.runtime.KernelRuntime.defaultRuntime().declaration();
    }

    // ==================== set ====================

    public void setAgentConfig(AgentConfig agentConfig) {
        if (agentConfig == null) {
            this.agentConfig = new AgentConfig();
        }
        this.agentConfig = agentConfig;
    }

    public void setWorkspaceConfig(WorkspaceConfig workspaceConfig) {
        if (workspaceConfig == null){
            workspaceConfig = new WorkspaceConfig();
        }
        this.workspaceConfig = workspaceConfig;
    }

    public void setSseClientSimpleConfig(SseClientConfig sseClientSimpleConfig) {
        if (sseClientSimpleConfig == null) {
            this.sseClientSimpleConfig = new SseClientConfig();
        }
        this.sseClientSimpleConfig = sseClientSimpleConfig;
    }

    public void setModels(List<ModelApiConfig> models) {
        if (models == null) {
            this.models = new ArrayList<>();
        }
        this.models = models;
    }

    public void setEmbeddings(List<EmbeddingApiConfig> embeddings) {
        if (embeddings == null) {
            throw new IllegalArgumentException("不允许为 null");
        }
        this.embeddings = embeddings;
    }

    // ==================== setters（声明域扩展） ====================

    public void setPrompts(PromptConfig prompts) {
        this.prompts = prompts == null ? new PromptConfig() : prompts;
    }

    public void setToolProviders(List<ToolProviderDecl> toolProviders) {
        this.toolProviders = toolProviders == null ? new ArrayList<>() : toolProviders;
    }

    public void setDefaults(Map<String, Object> defaults) {
        this.defaults = defaults == null ? new LinkedHashMap<>() : defaults;
    }

    public void setPlugins(Map<String, PluginSettings> plugins) {
        this.plugins = plugins == null ? new LinkedHashMap<>() : plugins;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
    }

    // ==================== 导出 JSON  ====================

    /**
     * 导出当前配置为格式化 JSON 字符串（凭据内联，兼容旧调用）
     */
    public String exportToJson() {
        return exportToJson(CredentialPolicy.INLINE);
    }

    /**
     * 导出声明快照（编排运行时协议 · declaration.json）。
     *
     * @param policy 凭据策略：INLINE 原文 / REFERENCE 用 ${ENV:...} 占位
     */
    public String exportToJson(CredentialPolicy policy) {
        CredentialPolicy p = policy == null ? CredentialPolicy.INLINE : policy;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", schemaVersion);
        root.put("agentConfig", agentConfig);
        root.put("workspaceConfig", workspaceConfig);
        root.put("sseClientSimpleConfig", sseClientSimpleConfig);
        root.put("models", maskedModels(p));
        root.put("embeddings", embeddings);
        root.put("prompts", prompts);
        root.put("toolProviders", resolveToolProviders());
        root.put("defaults", defaults);
        root.put("plugins", plugins);
        root.put("appSettingsSchema", appSettingsSchema);
        // 应用级设置值：schema 里标注 secret 的键按凭据策略 mask（与模型 apiKey 同口径）
        Map<String, Object> maskedApp = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : appSettings.entrySet()) {
            Object v = e.getValue();
            for (AppSettingDef d : appSettingsSchema) {
                if (d != null && e.getKey().equals(d.getKey()) && d.isSecret()) {
                    v = p.mask(v == null ? null : String.valueOf(v));
                    break;
                }
            }
            maskedApp.put(e.getKey(), v);
        }
        root.put("appSettings", maskedApp);
        return JsonMapper.toJsonString(root);
    }

    /**
     * 工具声明：显式设置过则原样导出；否则从注册表快照生成（声明域收归 · P1）。
     * 快照按 source 分组（core / app:&lt;hostId&gt; / plugin:&lt;id&gt;），供导入端做存在性校验。
     */
    private List<ToolProviderDecl> resolveToolProviders() {
        if (toolProviders != null && !toolProviders.isEmpty()) {
            return toolProviders;
        }
        List<ToolProviderDecl> list = new ArrayList<>();
        try {
            Map<String, List<String>> grouped = boundRuntime != null
                    ? boundRuntime.toolRegistry().groupBySource()
                    : net.itzq.mira.modules.ai.tool.FCUtil.groupBySource();
            for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
                ToolProviderDecl d = new ToolProviderDecl();
                d.setType("builtin");
                d.setSource(e.getKey());
                d.setNames(e.getValue());
                list.add(d);
            }
        } catch (Exception ex) {
            log.warn("工具注册表快照失败: {}", ex.getMessage());
        }
        return list;
    }

    /** 按凭据策略输出模型清单（apiKey 走策略；apiHeaders 中的敏感值暂不处理，留待后续策略细化） */
    private List<Map<String, Object>> maskedModels(CredentialPolicy policy) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModelApiConfig m : models) {
            Map<String, Object> item = JSON.parseObject(JSON.toJSONString(m));
            if (item != null) {
                Object key = item.get("apiKey");
                if (key != null) {
                    item.put("apiKey", policy.mask(String.valueOf(key)));
                }
                out.add(item);
            }
        }
        return out;
    }

    // ==================== 导入 JSON ====================

    /**
     * 从 JSON 字符串导入配置
     */
    public ValidationReport importFromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("配置 JSON 不能为空");
        }
        JSONObject root = JSON.parseObject(json);
        if (root == null) {
            throw new IllegalArgumentException("配置 JSON 解析失败");
        }
        ValidationReport report = new ValidationReport();

        // schemaVersion（缺省视为 1.0.0；无法识别的 major 记致命）
        String ver = root.getString("schemaVersion");
        if (ver != null && !ver.trim().isEmpty()) {
            if (!ver.startsWith("1.")) {
                report.addWarning("声明 schemaVersion=" + ver + " 非当前支持的 1.x，按兼容模式读取");
            }
            setSchemaVersion(ver);
        }

        // agentConfig
        JSONObject ac = root.getJSONObject("agentConfig");
        if (ac != null) {
            setAgentConfig(ac.to(AgentConfig.class));
        }

        // workspaceConfig
        JSONObject wkc = root.getJSONObject("workspaceConfig");
        if (wkc != null) {
            setWorkspaceConfig(wkc.to(WorkspaceConfig.class));
        }

        // sseClientSimpleConfig
        JSONObject sc = root.getJSONObject("sseClientSimpleConfig");
        if (sc != null) {
            setSseClientSimpleConfig(sc.to(SseClientConfig.class));
        }

        // models（凭据引用未解析 → warning，不阻断）
        JSONArray modelsArr = root.getJSONArray("models");
        if (modelsArr != null) {
            List<ModelApiConfig> list = modelsArr.toJavaList(ModelApiConfig.class);
            setModels(list);
            for (ModelApiConfig m : list) {
                if (m != null && m.getApiKey() != null && m.getApiKey().startsWith("${")) {
                    report.addWarning("模型[" + m.getAlias() + "] 凭据为引用形态且未解析: " + m.getApiKey());
                }
            }
        }

        // embeddings
        JSONArray embArr = root.getJSONArray("embeddings");
        if (embArr != null) {
            setEmbeddings(embArr.toJavaList(EmbeddingApiConfig.class));
        }

        // prompts（提示词域）
        JSONObject promptsObj = root.getJSONObject("prompts");
        if (promptsObj != null) {
            setPrompts(promptsObj.to(PromptConfig.class));
        }

        // toolProviders（工具域）+ 注册表存在性校验（缺注册 → missing 清单）
        JSONArray tpArr = root.getJSONArray("toolProviders");
        if (tpArr != null) {
            List<ToolProviderDecl> decls = tpArr.toJavaList(ToolProviderDecl.class);
            setToolProviders(decls);
            for (ToolProviderDecl d : decls) {
                if (d == null || !"builtin".equalsIgnoreCase(d.getType()) || d.getNames() == null) {
                    continue; // http/mcp 的存在性由宿主自身配置保证
                }
                for (String name : d.getNames()) {
                    boolean missingTool = boundRuntime != null
                            ? boundRuntime.toolRegistry().getTool(name) == null
                            : net.itzq.mira.modules.ai.tool.FCUtil.getTool(name) == null;
                    if (missingTool) {
                        report.addMissing("tool", name,
                                "builtin 工具未在本运行时注册（source=" + d.getSource() + "）");
                    }
                }
            }
        }

        // defaults（默认值域）
        JSONObject defaultsObj = root.getJSONObject("defaults");
        if (defaultsObj != null) {
            setDefaults(new LinkedHashMap<>(defaultsObj));
        }

        // plugins（插件域）
        JSONObject pluginsObj = root.getJSONObject("plugins");
        if (pluginsObj != null) {
            Map<String, PluginSettings> map = new LinkedHashMap<>();
            for (String pid : pluginsObj.keySet()) {
                JSONObject one = pluginsObj.getJSONObject(pid);
                map.put(pid, one == null ? new PluginSettings() : one.to(PluginSettings.class));
            }
            setPlugins(map);
        }

        // appSettingsSchema（应用级设置声明：宿主自有工具/功能的 schema）
        JSONArray assArr = root.getJSONArray("appSettingsSchema");
        if (assArr != null) {
            setAppSettingsSchema(assArr.toJavaList(AppSettingDef.class));
        }

        // appSettings（应用级设置值；secret 为 ${ENV} 未解析 → warning，不阻断）
        JSONObject asObj = root.getJSONObject("appSettings");
        if (asObj != null) {
            Map<String, Object> vals = new LinkedHashMap<>(asObj);
            for (AppSettingDef d : appSettingsSchema) {
                if (d == null || !d.isSecret()) {
                    continue;
                }
                Object v = vals.get(d.getKey());
                if (v != null && String.valueOf(v).startsWith("${")) {
                    report.addWarning("应用级设置[" + d.getKey() + "] 凭据为引用形态且未解析: " + v);
                }
            }
            this.appSettings = vals;
        }

        // 导入后应用到各子系统。
        // P5 多实例：**必须应用到本实例的注册表**——静态 applyAll() 走的是默认运行时，
        // 非默认实例（独立运行时/编排包导入）用它会导致"声明更新了、模型没注册进自己"。
        if (boundRuntime != null) {
            applyAllInstance();
        } else {
            applyAll();
        }
        log.info("全局配置已从 JSON 导入并应用: {}", report.summary());
        return report;
    }

    /** 提示词域：当前基线系统提示词（未覆盖时从内核默认资产装载） */
    public String baseSystemPrompt() {
        return prompts == null ? "" : prompts.resolve();
    }

    // ==================== 插件设置读写（编排运行时协议 · 插件配置扩展点） ====================

    /**
     * 读取插件设置项（编排运行时协议：插件执行时直读，不再依赖宿主透传）。
     *
     * @param pluginId 插件 id（如 ai-chat-node）
     * @param key      设置键（schema 声明的短名）
     * @return 字符串值；插件未配置或键不存在返回 {@code null}
     */
    public String pluginSetting(String pluginId, String key) {
        PluginSettings ps = pluginSettingsOf(pluginId);
        if (ps == null || ps.getSettings() == null) {
            return null;
        }
        Object v = ps.getSettings().get(key);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 读取插件设置项，缺失时回落默认值。
     *
     * @param def 默认值（可 null）
     */
    public String pluginSetting(String pluginId, String key, String def) {
        String v = pluginSetting(pluginId, key);
        return (v == null || v.trim().isEmpty()) ? def : v;
    }

    /** 读取插件设置项（数值形态，缺失/不可解析回落默认） */
    public long pluginSettingLong(String pluginId, String key, long def) {
        String v = pluginSetting(pluginId, key);
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            log.warn("插件设置[{}.{}]不是合法整数，回落默认值: {}", pluginId, key, v);
            return def;
        }
    }

    /** 读取插件设置项（布尔形态；"1"/"true" 为真，缺失回落默认） */
    public boolean pluginSettingBool(String pluginId, String key, boolean def) {
        String v = pluginSetting(pluginId, key);
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        String s = v.trim();
        return "1".equals(s) || Boolean.parseBoolean(s);
    }

    /** 写入插件设置（宿主 apply 编辑时调用；导出声明即携带） */
    public void setPluginSetting(String pluginId, String key, Object value) {
        if (pluginId == null || pluginId.trim().isEmpty() || key == null) {
            return;
        }
        PluginSettings ps = pluginSettingsOf(pluginId);
        if (ps == null) {
            ps = new PluginSettings();
            plugins.put(pluginId, ps);
        }
        if (ps.getSettings() == null) {
            ps.setSettings(new LinkedHashMap<String, Object>());
        }
        if (value == null) {
            ps.getSettings().remove(key);
        } else {
            ps.getSettings().put(key, value);
        }
    }

    /** 批量写入插件设置（宿主设置面板保存时调用） */
    public void setPluginSettings(String pluginId, Map<String, Object> values) {
        if (values == null) {
            return;
        }
        for (Map.Entry<String, Object> e : values.entrySet()) {
            setPluginSetting(pluginId, e.getKey(), e.getValue());
        }
    }

    /** 插件设置容器（不存在返回 null） */
    public PluginSettings pluginSettingsOf(String pluginId) {
        return pluginId == null ? null : plugins.get(pluginId);
    }

    // ==================== 各子系统应用逻辑 ====================

    /** 应用全部配置到各子系统 */
    public static void applyAll() {
        config().applyModels();
        config().applyEmbeddings();
    }

    public static void initWorkSpace() {

        log.info("===  初始化 Workspace ===");


        FileWorkspace.init(config().getWorkspaceConfig());

        log.info("Workspace 初始化完成");
        log.info("数据目录: " + config().getWorkspaceConfig().getDataDir());
    }

    public static void initVfsSpace() {
        log.info("===  初始化 VFS ===");

        VFS.init(config().getWorkspaceConfig());

        log.info("VFS 初始化完成");
        log.info("数据目录: " + config().getWorkspaceConfig().getDataDir());
    }

    private void applyModels() {
        if (boundRuntime != null) {
            // P5：应用进本运行时的模型注册表（多实例互不可见）
            boundRuntime.modelRegistry().reset(models);
            if (agentConfig.getDefaultModel() != null && !agentConfig.getDefaultModel().isEmpty()) {
                boundRuntime.modelRegistry().setDefaultModel(agentConfig.getDefaultModel());
            }
            return;
        }
        ApiProviderManage.reset(models);
        if (agentConfig.getDefaultModel() != null && !agentConfig.getDefaultModel().isEmpty()) {
            ApiProviderManage.getInstance().setDefaultModel(agentConfig.getDefaultModel());
        }
    }

    private void applyEmbeddings() {
        if (boundRuntime != null) {
            boundRuntime.embeddingRegistry().reset(embeddings);
            if (agentConfig.getDefaultEmbedding() != null && !agentConfig.getDefaultEmbedding().isEmpty()) {
                boundRuntime.embeddingRegistry().setDefaultProvider(agentConfig.getDefaultEmbedding());
            }
            return;
        }
        EmbeddingProviderManage.reset(embeddings);
        if (agentConfig.getDefaultEmbedding() != null && !agentConfig.getDefaultEmbedding().isEmpty()) {
            EmbeddingProviderManage.getInstance().setDefaultProvider(agentConfig.getDefaultEmbedding());
        }
    }

    /**
     * 实例级"应用全部配置"（P5）：只作用于本运行时持有的注册表，
     * 与静态 {@link #applyAll()}（作用于默认运行时）区分。
     */
    public void applyAllInstance() {
        applyModels();
        applyEmbeddings();
    }


}
