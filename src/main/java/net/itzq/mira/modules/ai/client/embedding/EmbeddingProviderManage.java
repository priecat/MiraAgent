package net.itzq.mira.modules.ai.client.embedding;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding 提供者管理
 *
 * 与 ApiProviderManage 模式一致：
 * 1. 按别名注册 Embedding 提供者
 * 2. 通过别名获取实例
 * 3. 提供静态 embed 便捷方法：通过别名获取实例后直接获取向量
 *
 * @author tangzq
 */
@Slf4j
public class EmbeddingProviderManage {

    private static volatile EmbeddingProviderManage instance;

    private Map<String, EmbeddingProvider> providers = new ConcurrentHashMap<>();

    private static volatile String defaultProvider = "";

    private EmbeddingProviderManage() {

    }

    public static EmbeddingProviderManage getInstance() {
        if (instance == null) {
            synchronized (EmbeddingProviderManage.class) {
                if (instance == null) {
                    instance = new EmbeddingProviderManage();
                }
            }
        }
        return instance;
    }

    // 注册 Embedding 提供者（通过配置构建 HttpEmbeddingProvider）
    public void registerProvider(EmbeddingApiConfig config) {
        if (config == null || config.getAlias() == null || config.getAlias().isEmpty()) {
            throw new IllegalArgumentException("EmbeddingRegistrationConfig 或 alias 不能为空");
        }
        HttpEmbeddingProvider provider = new HttpEmbeddingProvider(
                config.getApiUrl(),
                config.getApiKey(),
                config.getModel(),
                config.getDimension(),
                config.getConnectTimeout(),
                config.getReadTimeout());
        providers.put(config.getAlias(), provider);
    }

    // 注册自定义 Embedding 提供者实例
    public void registerProvider(String alias, EmbeddingProvider provider) {
        if (alias == null || alias.isEmpty() || provider == null) {
            throw new IllegalArgumentException("alias 和 provider 不能为空");
        }
        providers.put(alias, provider);
    }

    // 注销提供者
    public void unregisterProvider(String alias) {
        providers.remove(alias);
    }

    // 获取提供者实例（未注册时抛出异常）
    public static EmbeddingProvider getProvider(String alias) {
        return getProvider(alias, true);
    }

    public static EmbeddingProvider getProvider(String alias, boolean verify) {
        EmbeddingProvider provider = getInstance().providers.get(alias);

        if (provider == null && verify) {
            throw new RuntimeException("EmbeddingProvider 未注册 ：" + String.valueOf(alias));
        }

        if (provider == null) {
            String msg = "EmbeddingProvider 未注册 ：" + String.valueOf(alias);
            log.warn(msg);
        }

        return provider;
    }

    /**
     * 通过别名获取实例并获取向量
     *
     * @param alias 别名
     * @param text  待向量化文本
     * @return 向量（失败返回 null）
     */
    public static float[] embed(String alias, String text) {
        EmbeddingProvider provider = getProvider(alias);
        return provider.embed(text);
    }

    /**
     * 使用默认提供者获取向量
     *
     * @param text 待向量化文本
     * @return 向量（失败返回 null）
     */
    public static float[] embed(String text) {
        return embed(defaultProvider, text);
    }

    // 设置默认提供者别名
    public void setDefaultProvider(String alias) {
        defaultProvider = alias;
    }

    public static String getDefaultProvider() {
        return defaultProvider;
    }

    // 获取所有已注册的别名
    public Set<String> aliases() {
        return providers.keySet();
    }


    public static void reset(List<EmbeddingApiConfig> configs) {

        Map<String, EmbeddingProvider> newConfig = new ConcurrentHashMap<>();

        if (configs != null) {
            for (EmbeddingApiConfig ec : configs) {
                if (ec == null || ec.getAlias() == null || ec.getAlias().isEmpty()) {
                    log.warn("跳过无效 Embedding 配置（alias 为空）");
                    continue;
                }

                HttpEmbeddingProvider provider = new HttpEmbeddingProvider(
                        ec.getApiUrl(),
                        ec.getApiKey(),
                        ec.getModel(),
                        ec.getDimension(),
                        ec.getConnectTimeout(),
                        ec.getReadTimeout());
                newConfig.put(ec.getAlias(), provider);

                log.info("注册 Embedding: {}", ec.getAlias());
            }
        }

        getInstance().providers = newConfig;
    }
}
