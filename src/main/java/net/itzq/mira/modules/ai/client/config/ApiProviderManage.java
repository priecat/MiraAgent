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
 *  AIProviderConfig
 *
 *  @author tangzq
 */
@Slf4j
public class ApiProviderManage {

    private static volatile ApiProviderManage instance;

    private Map<String, OpenAICompatibleChatService> providers = new ConcurrentHashMap<>();

    /** 图像生成服务（alias → service），与 chat 相互独立，不影响既有链路 */
    private volatile Map<String, OpenAICompatibleImageService> imageProviders = new ConcurrentHashMap<>();

    /** 内容审查服务（alias → service） */
    private volatile Map<String, OpenAICompatibleModerationService> moderationProviders = new ConcurrentHashMap<>();

    /** 语音服务（alias → service，transcription/speech 以不同 alias 分别注册） */
    private volatile Map<String, OpenAICompatibleAudioService> audioProviders = new ConcurrentHashMap<>();

    private static String defaultModel = "";

    private ApiProviderManage() {

    }

    public static ApiProviderManage getInstance() {
        if (instance == null) {
            synchronized (ApiProviderManage.class) {
                if (instance == null) {
                    instance = new ApiProviderManage();
                }
            }
        }
        return instance;
    }

    // ==================== 图像 / 审查 / 语音服务注册与路由 ====================

    /** 注册图像生成服务 */
    public void registerImageService(ModelApiConfig config) {
        imageProviders.put(config.getAlias(), new OpenAICompatibleImageService(config));
    }

    /** 注册内容审查服务 */
    public void registerModerationService(ModelApiConfig config) {
        moderationProviders.put(config.getAlias(), new OpenAICompatibleModerationService(config));
    }

    /** 注册语音服务（transcription 与 speech 需以不同 alias、不同 endpoint 分别注册） */
    public void registerAudioService(ModelApiConfig config) {
        audioProviders.put(config.getAlias(), new OpenAICompatibleAudioService(config));
    }

    public static OpenAICompatibleImageService getImageService(String alias) {
        return getImageService(alias, true);
    }

    public static OpenAICompatibleImageService getImageService(String alias, boolean verify) {
        OpenAICompatibleImageService service = getInstance().imageProviders.get(alias);
        if (service == null && verify) {
            throw new RuntimeException("ImageService 未注册 ：" + String.valueOf(alias));
        }
        if (service == null) {
            log.warn("ImageService 未注册 ：{}", String.valueOf(alias));
        }
        return service;
    }

    public static OpenAICompatibleModerationService getModerationService(String alias) {
        return getModerationService(alias, true);
    }

    public static OpenAICompatibleModerationService getModerationService(String alias, boolean verify) {
        OpenAICompatibleModerationService service = getInstance().moderationProviders.get(alias);
        if (service == null && verify) {
            throw new RuntimeException("ModerationService 未注册 ：" + String.valueOf(alias));
        }
        if (service == null) {
            log.warn("ModerationService 未注册 ：{}", String.valueOf(alias));
        }
        return service;
    }

    public static OpenAICompatibleAudioService getAudioService(String alias) {
        return getAudioService(alias, true);
    }

    public static OpenAICompatibleAudioService getAudioService(String alias, boolean verify) {
        OpenAICompatibleAudioService service = getInstance().audioProviders.get(alias);
        if (service == null && verify) {
            throw new RuntimeException("AudioService 未注册 ：" + String.valueOf(alias));
        }
        if (service == null) {
            log.warn("AudioService 未注册 ：{}", String.valueOf(alias));
        }
        return service;
    }

    /** 全量重建图像服务（原子替换） */
    public static void resetImages(List<ModelApiConfig> configs) {
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
        getInstance().imageProviders = newConfig;
    }

    /** 全量重建审查服务（原子替换） */
    public static void resetModerations(List<ModelApiConfig> configs) {
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
        getInstance().moderationProviders = newConfig;
    }

    /** 全量重建语音服务（原子替换） */
    public static void resetAudios(List<ModelApiConfig> configs) {
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
        getInstance().audioProviders = newConfig;
    }

    // ==================== 对话服务（既有链路） ====================

    // 注册模型
    public void registerModel(ModelApiConfig config) {
        providers.put(config.getAlias(), new OpenAICompatibleChatService(config));
    }

    // 获取服务实例
    public static OpenAICompatibleChatService getChatService(String modelAlias) {
        return getChatService(modelAlias, true);
    }

    public static OpenAICompatibleChatService getChatService(String modelAlias, boolean verify) {
        OpenAICompatibleChatService openAICompatibleChatService = getInstance().providers.get(modelAlias);

        if (openAICompatibleChatService == null && verify) {
            throw new RuntimeException("ChatService 未注册 ：" + String.valueOf(modelAlias));
        }

        if (openAICompatibleChatService == null) {
            String msg = "ChatService 未注册 ：" + String.valueOf(modelAlias);
            log.warn(msg);
        }

        return openAICompatibleChatService;
    }

    // 注册默认的服务提供者和模型
    public void setDefaultModel(String modelAlias) {
        this.defaultModel = modelAlias;
    }

    public static String getDefaultModel() {
        return defaultModel;
    }

    public static void reset(List<ModelApiConfig> models) {

        Map<String, OpenAICompatibleChatService> newConfig = new ConcurrentHashMap<>();

        if (models != null) {
            for (ModelApiConfig mc : models) {
                if (mc == null || mc.getAlias() == null || mc.getAlias().isEmpty()) {
                    log.warn("跳过无效模型配置（alias 为空）");
                    continue;
                }
                newConfig.put(mc.getAlias(), new OpenAICompatibleChatService(mc));
                log.info("注册对话模型: {}", mc.getAlias());
            }
        }

        getInstance().providers = newConfig;
    }


}
