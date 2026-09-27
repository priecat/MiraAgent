package net.itzq.mira.modules.ai.client.config;

import net.itzq.mira.modules.ai.client.handle.OpenAICompatibleAudioService;
import net.itzq.mira.modules.ai.client.handle.OpenAICompatibleChatService;
import net.itzq.mira.modules.ai.client.handle.OpenAICompatibleImageService;
import net.itzq.mira.modules.ai.client.handle.OpenAICompatibleModerationService;
import net.itzq.mira.modules.runtime.KernelRuntime;

import java.util.List;

/**
 * ApiProviderManage —— 模型服务注册表的**兼容 facade**。
 *
 * <p>起注册表是实例组件 {@link ModelRegistry}（每个 {@code KernelRuntime} 一份，
 * 模型/凭据互不可见）。本类静态方法全部委托默认运行时的注册表，存量调用点零改动。
 *
 * @deprecated 改用 {@code holder.getRuntime().modelRegistry()}。
 */
@Deprecated
public class ApiProviderManage {

    private ApiProviderManage() {
    }

    private static ModelRegistry registry() {
        return KernelRuntime.defaultRuntime().modelRegistry();
    }

    /** 兼容入口：返回默认运行时的注册表（原单例语义的等价物） */
    public static ModelRegistry getInstance() {
        return registry();
    }

    // ==================== 图像 / 审查 / 语音 ====================

    public static void registerImageService(ModelApiConfig config) {
        registry().registerImageService(config);
    }

    public static void registerModerationService(ModelApiConfig config) {
        registry().registerModerationService(config);
    }

    public static void registerAudioService(ModelApiConfig config) {
        registry().registerAudioService(config);
    }

    public static OpenAICompatibleImageService getImageService(String alias) {
        return registry().getImageService(alias);
    }

    public static OpenAICompatibleImageService getImageService(String alias, boolean verify) {
        return registry().getImageService(alias, verify);
    }

    public static OpenAICompatibleModerationService getModerationService(String alias) {
        return registry().getModerationService(alias);
    }

    public static OpenAICompatibleModerationService getModerationService(String alias, boolean verify) {
        return registry().getModerationService(alias, verify);
    }

    public static OpenAICompatibleAudioService getAudioService(String alias) {
        return registry().getAudioService(alias);
    }

    public static OpenAICompatibleAudioService getAudioService(String alias, boolean verify) {
        return registry().getAudioService(alias, verify);
    }

    public static void resetImages(List<ModelApiConfig> configs) {
        registry().resetImages(configs);
    }

    public static void resetModerations(List<ModelApiConfig> configs) {
        registry().resetModerations(configs);
    }

    public static void resetAudios(List<ModelApiConfig> configs) {
        registry().resetAudios(configs);
    }

    // ==================== 对话服务 ====================

    public static void registerModel(ModelApiConfig config) {
        registry().registerModel(config);
    }

    public static OpenAICompatibleChatService getChatService(String modelAlias) {
        return registry().getChatService(modelAlias);
    }

    public static OpenAICompatibleChatService getChatService(String modelAlias, boolean verify) {
        return registry().getChatService(modelAlias, verify);
    }

    public static void setDefaultModel(String modelAlias) {
        registry().setDefaultModel(modelAlias);
    }

    public static String getDefaultModel() {
        return registry().getDefaultModel();
    }

    public static void reset(List<ModelApiConfig> models) {
        registry().reset(models);
    }
}
