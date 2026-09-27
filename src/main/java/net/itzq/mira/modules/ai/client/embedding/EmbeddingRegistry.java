package net.itzq.mira.modules.ai.client.embedding;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding 注册表（编排运行时协议 · 实例组件）。
 *
 * <p>实例化：原 {@link EmbeddingProviderManage} 的提供者表与 defaultProvider 平移到本类，
 * 静态单例语义改为实例私有——每个内核运行时（KernelRuntime）拥有独立 embedding 注册表；
 * {@link EmbeddingProviderManage} 降级为委托默认运行时的静态 facade。
 */
@Slf4j
public class EmbeddingRegistry {

    private volatile Map<String, EmbeddingProvider> providers = new ConcurrentHashMap<>();

    private volatile String defaultProvider = "";

    public EmbeddingRegistry() {
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

    /** 注册自定义 Embedding 提供者实例 */
    public void registerProvider(String alias, EmbeddingProvider provider) {
        if (alias == null || alias.isEmpty() || provider == null) {
            throw new IllegalArgumentException("alias 和 provider 不能为空");
        }
        providers.put(alias, provider);
    }

    /** 注销提供者 */
    public void unregisterProvider(String alias) {
        providers.remove(alias);
    }

    /** 获取提供者实例（未注册时抛出异常） */
    public EmbeddingProvider getProvider(String alias) {
        return getProvider(alias, true);
    }

    public EmbeddingProvider getProvider(String alias, boolean verify) {
        EmbeddingProvider provider = providers.get(alias);
        if (provider == null && verify) {
            throw new RuntimeException("EmbeddingProvider 未注册 ：" + String.valueOf(alias));
        }
        if (provider == null) {
            log.warn("EmbeddingProvider 未注册 ：{}", String.valueOf(alias));
        }
        return provider;
    }

    /** 通过别名获取实例并获取向量（失败返回 null） */
    public float[] embed(String alias, String text) {
        EmbeddingProvider provider = getProvider(alias);
        return provider.embed(text);
    }

    /** 使用默认提供者获取向量 */
    public float[] embed(String text) {
        return embed(defaultProvider, text);
    }

    /** 设置默认提供者别名 */
    public void setDefaultProvider(String alias) {
        this.defaultProvider = alias;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    /** 获取所有已注册的别名 */
    public Set<String> aliases() {
        return providers.keySet();
    }

    /** 全量重建（原子替换） */
    public void reset(List<EmbeddingApiConfig> configs) {
        Map<String, EmbeddingProvider> newConfig = new ConcurrentHashMap<>();
        if (configs != null) {
            for (EmbeddingApiConfig ec : configs) {
                if (ec == null || ec.getAlias() == null || ec.getAlias().isEmpty()) {
                    log.warn("跳过无效 Embedding 配置（alias 为空）");
                    continue;
                }
                newConfig.put(ec.getAlias(), new HttpEmbeddingProvider(
                        ec.getApiUrl(),
                        ec.getApiKey(),
                        ec.getModel(),
                        ec.getDimension(),
                        ec.getConnectTimeout(),
                        ec.getReadTimeout()));
                log.info("注册 Embedding: {}", ec.getAlias());
            }
        }
        this.providers = newConfig;
    }

    /** 清空注册（{@code KernelRuntime.close()} 用：即用即释放） */
    public void clear() {
        providers = new ConcurrentHashMap<>();
        defaultProvider = "";
    }
}
