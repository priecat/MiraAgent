package net.itzq.mira.modules.ai.client.config;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.client.handle.OpenAICompatibleAudioService;
import net.itzq.mira.modules.ai.client.handle.OpenAICompatibleChatService;
import net.itzq.mira.modules.ai.client.handle.OpenAICompatibleImageService;
import net.itzq.mira.modules.ai.client.handle.OpenAICompatibleModerationService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型服务注册表（编排运行时协议 · 实例组件）。
 *
 * <p>P5 实例化：原 {@link ApiProviderManage} 的四个服务表与 defaultModel 平移到本类，
 * 静态单例语义改为**实例私有**——每个内核运行时（KernelRuntime）拥有独立的模型注册表，
 * 同进程多实例的模型/凭据互不可见；{@link ApiProviderManage} 降级为委托默认运行时的静态 facade。
 */
@Slf4j
public class ModelRegistry {

    private final Map<String, OpenAICompatibleChatService> providers = new ConcurrentHashMap<>();

    /** 图像生成服务（alias → service），与 chat 相互独立 */
    private volatile Map<String, OpenAICompatibleImageService> imageProviders = new ConcurrentHashMap<>();

    /** 内容审查服务（alias → service） */
    private volatile Map<String, OpenAICompatibleModerationService> moderationProviders = new ConcurrentHashMap<>();

    /** 语音服务（alias → service，transcription/speech 以不同 alias 分别注册） */
    private volatile Map<String, OpenAICompatibleAudioService> audioProviders = new ConcurrentHashMap<>();

    private volatile String defaultModel = "";

    /**
     * 本实例的工具注册表（P5）：构造对话服务时注入，使"请求里的 functions → Tool 实体"
     * 从**同一运行时**的注册表解析（多实例隔离）。未绑定则回落默认运行时的注册表。
     */
    private volatile net.itzq.mira.modules.ai.tool.ToolRegistry toolRegistry;

    public ModelRegistry() {
    }

    /** 绑定本运行时的工具注册表（KernelRuntime 构造时调用一次） */
    public void bindToolRegistry(net.itzq.mira.modules.ai.tool.ToolRegistry registry) {
        this.toolRegistry = registry;
    }

    /** 生效的工具注册表（未绑定 → 默认运行时） */
    private net.itzq.mira.modules.ai.tool.ToolRegistry tools() {
        net.itzq.mira.modules.ai.tool.ToolRegistry t = this.toolRegistry;
        if (t != null) {
            return t;
        }
        return net.itzq.mira.modules.runtime.KernelRuntime.defaultRuntime().toolRegistry();
    }

    // ==================== 图像 / 审查 / 语音服务 ====================

    /**
     * 本实例生效的 SSE 超时配置（P5 多实例）：经工具注册表反向引用取**所属运行时**的声明，
     * 未绑定 / 未配置回落默认运行时声明。service 创建时以此为其请求超时快照。
     */
    private net.itzq.mira.modules.config.SseClientConfig sseTimeouts() {
        net.itzq.mira.modules.ai.tool.ToolRegistry t = this.toolRegistry;
        net.itzq.mira.modules.config.SseClientConfig cfg = null;
        if (t != null && t.getRuntime() != null) {
            cfg = t.getRuntime().declaration().getSseClientSimpleConfig();
        }
        if (cfg == null) {
            cfg = net.itzq.mira.modules.config.GlobalConfigManager.config().getSseClientSimpleConfig();
        }
        return cfg != null ? cfg : new net.itzq.mira.modules.config.SseClientConfig();
    }

    /** 注册图像生成服务 */
    public void registerImageService(ModelApiConfig config) {
        imageProviders.put(config.getAlias(), new OpenAICompatibleImageService(config, sseTimeouts()));
    }

    /** 注册内容审查服务 */
    public void registerModerationService(ModelApiConfig config) {
        moderationProviders.put(config.getAlias(), new OpenAICompatibleModerationService(config, sseTimeouts()));
    }

    /** 注册语音服务（transcription 与 speech 需以不同 alias、不同 endpoint 分别注册） */
    public void registerAudioService(ModelApiConfig config) {
        audioProviders.put(config.getAlias(), new OpenAICompatibleAudioService(config, sseTimeouts()));
    }

    public OpenAICompatibleImageService getImageService(String alias) {
        return getImageService(alias, true);
    }

    public OpenAICompatibleImageService getImageService(String alias, boolean verify) {
        OpenAICompatibleImageService service = imageProviders.get(alias);
        if (service == null && verify) {
            throw new RuntimeException("ImageService 未注册 ：" + String.valueOf(alias));
        }
        if (service == null) {
            log.warn("ImageService 未注册 ：{}", String.valueOf(alias));
        }
        return service;
    }

    public OpenAICompatibleModerationService getModerationService(String alias) {
        return getModerationService(alias, true);
    }

    public OpenAICompatibleModerationService getModerationService(String alias, boolean verify) {
        OpenAICompatibleModerationService service = moderationProviders.get(alias);
        if (service == null && verify) {
            throw new RuntimeException("ModerationService 未注册 ：" + String.valueOf(alias));
        }
        if (service == null) {
            log.warn("ModerationService 未注册 ：{}", String.valueOf(alias));
        }
        return service;
    }

    public OpenAICompatibleAudioService getAudioService(String alias) {
        return getAudioService(alias, true);
    }

    public OpenAICompatibleAudioService getAudioService(String alias, boolean verify) {
        OpenAICompatibleAudioService service = audioProviders.get(alias);
        if (service == null && verify) {
            throw new RuntimeException("AudioService 未注册 ：" + String.valueOf(alias));
        }
        if (service == null) {
            log.warn("AudioService 未注册 ：{}", String.valueOf(alias));
        }
        return service;
    }

    /** 全量重建图像服务（原子替换） */
    public void resetImages(List<ModelApiConfig> configs) {
        Map<String, OpenAICompatibleImageService> newConfig = new ConcurrentHashMap<>();
        if (configs != null) {
            for (ModelApiConfig mc : configs) {
                if (mc == null || mc.getAlias() == null || mc.getAlias().isEmpty()) {
                    log.warn("跳过无效图像服务配置（alias 为空）");
                    continue;
                }
                newConfig.put(mc.getAlias(), new OpenAICompatibleImageService(mc));
                log.info("注册图像服务: {}", mc.getAlias());
            }
        }
        this.imageProviders = newConfig;
    }

    /** 全量重建审查服务（原子替换） */
    public void resetModerations(List<ModelApiConfig> configs) {
        Map<String, OpenAICompatibleModerationService> newConfig = new ConcurrentHashMap<>();
        if (configs != null) {
            for (ModelApiConfig mc : configs) {
                if (mc == null || mc.getAlias() == null || mc.getAlias().isEmpty()) {
                    log.warn("跳过无效审查服务配置（alias 为空）");
                    continue;
                }
                newConfig.put(mc.getAlias(), new OpenAICompatibleModerationService(mc));
                log.info("注册审查服务: {}", mc.getAlias());
            }
        }
        this.moderationProviders = newConfig;
    }

    /** 全量重建语音服务（原子替换） */
    public void resetAudios(List<ModelApiConfig> configs) {
        Map<String, OpenAICompatibleAudioService> newConfig = new ConcurrentHashMap<>();
        if (configs != null) {
            for (ModelApiConfig mc : configs) {
                if (mc == null || mc.getAlias() == null || mc.getAlias().isEmpty()) {
                    log.warn("跳过无效语音服务配置（alias 为空）");
                    continue;
                }
                newConfig.put(mc.getAlias(), new OpenAICompatibleAudioService(mc));
                log.info("注册语音服务: {}", mc.getAlias());
            }
        }
        this.audioProviders = newConfig;
    }

    // ==================== 对话服务 ====================

    /** 注册模型 */
    public void registerModel(ModelApiConfig config) {
        providers.put(config.getAlias(), new OpenAICompatibleChatService(config, tools()));
    }

    public OpenAICompatibleChatService getChatService(String modelAlias) {
        return getChatService(modelAlias, true);
    }

    public OpenAICompatibleChatService getChatService(String modelAlias, boolean verify) {
        OpenAICompatibleChatService service = providers.get(modelAlias);
        if (service == null && verify) {
            throw new RuntimeException("ChatService 未注册 ：" + String.valueOf(modelAlias));
        }
        if (service == null) {
            log.warn("ChatService 未注册 ：{}", String.valueOf(modelAlias));
        }
        return service;
    }

    /** 全量重建对话服务（原子替换） */
    public void reset(List<ModelApiConfig> models) {
        Map<String, OpenAICompatibleChatService> newConfig = new ConcurrentHashMap<>();
        if (models != null) {
            for (ModelApiConfig mc : models) {
                if (mc == null || mc.getAlias() == null || mc.getAlias().isEmpty()) {
                    log.warn("跳过无效模型配置（alias 为空）");
                    continue;
                }
                newConfig.put(mc.getAlias(), new OpenAICompatibleChatService(mc, tools()));
                log.info("注册对话模型: {}", mc.getAlias());
            }
        }
        this.providers.keySet().removeIf(k -> !newConfig.containsKey(k));
        this.providers.putAll(newConfig);
    }

    /** 设置默认模型（alias） */
    public void setDefaultModel(String modelAlias) {
        this.defaultModel = modelAlias;
    }

    /** 默认模型 alias */
    public String getDefaultModel() {
        return defaultModel;
    }

    /** 已注册的对话模型 alias 清单 */
    public java.util.Set<String> chatAliases() {
        return new java.util.LinkedHashSet<>(providers.keySet());
    }

    /** 清空全部服务（{@code KernelRuntime.close()} 用：即用即释放） */
    public void clear() {
        int chat = providers.size();
        providers.clear();
        imageProviders = new ConcurrentHashMap<>();
        moderationProviders = new ConcurrentHashMap<>();
        audioProviders = new ConcurrentHashMap<>();
        defaultModel = "";
        log.info("【ModelRegistry】已清空模型注册（chat={}）", chat);
    }
}
