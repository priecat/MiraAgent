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

    private GlobalConfigManager() {
    }

    // ==================== 单例 ====================

    private static volatile GlobalConfigManager instance;

    public static GlobalConfigManager config() {
        if (instance == null) {
            synchronized (GlobalConfigManager.class) {
                if (instance == null) {
                    instance = new GlobalConfigManager();
                }
            }
        }
        return instance;
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

    // ==================== 导出 JSON  ====================

    /**
     * 导出当前配置为格式化 JSON 字符串
     */
    public String exportToJson() {
        Map<String,Object> root = new LinkedHashMap<>();
        root.put("agentConfig", agentConfig);
        root.put("workspaceConfig", workspaceConfig);
        root.put("sseClientSimpleConfig", sseClientSimpleConfig);
        root.put("models", models);
        root.put("embeddings", embeddings);
        return JsonMapper.toJsonString(root);
    }

    // ==================== 导入 JSON ====================

    /**
     * 从 JSON 字符串导入配置
     */
    public void importFromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("配置 JSON 不能为空");
        }
        JSONObject root = JSON.parseObject(json);
        if (root == null) {
            throw new IllegalArgumentException("配置 JSON 解析失败");
        }

        // agentConfig
        JSONObject ac = root.getJSONObject("agentConfig");
        if (ac != null) {
            setAgentConfig(ac.to(AgentConfig.class));
        }

        // workspaceConfig
        JSONObject wkc = root.getJSONObject("workspaceConfig");
        if (ac != null) {
            setWorkspaceConfig(wkc.to(WorkspaceConfig.class));
        }

        // sseClientSimpleConfig
        JSONObject sc = root.getJSONObject("sseClientSimpleConfig");
        if (sc != null) {
            setSseClientSimpleConfig(sc.to(SseClientConfig.class));
        }

        // models
        JSONArray modelsArr = root.getJSONArray("models");
        if (modelsArr != null) {
            setModels(modelsArr.toJavaList(ModelApiConfig.class));
        }

        // embeddings
        JSONArray embArr = root.getJSONArray("embeddings");
        if (embArr != null) {
            setEmbeddings(embArr.toJavaList(EmbeddingApiConfig.class));
        }

        // 导入后应用到各子系统
        applyAll();
        log.info("全局配置已从 JSON 导入并应用");
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
        ApiProviderManage.reset(models);

        if (agentConfig.getDefaultModel() != null && !agentConfig.getDefaultModel().isEmpty()) {
            ApiProviderManage.getInstance().setDefaultModel(agentConfig.getDefaultModel());
        }
    }

    private void applyEmbeddings() {
        EmbeddingProviderManage.reset(embeddings);

        if (agentConfig.getDefaultEmbedding() != null && !agentConfig.getDefaultEmbedding().isEmpty()) {
            EmbeddingProviderManage.getInstance().setDefaultProvider(agentConfig.getDefaultEmbedding());
        }
    }


}
