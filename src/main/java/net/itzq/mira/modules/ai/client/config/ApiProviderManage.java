package net.itzq.mira.modules.ai.client.config;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.client.handle.OpenAICompatibleChatService;

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
