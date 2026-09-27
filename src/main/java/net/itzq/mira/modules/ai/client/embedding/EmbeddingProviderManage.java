package net.itzq.mira.modules.ai.client.embedding;

import net.itzq.mira.modules.runtime.KernelRuntime;

import java.util.List;
import java.util.Set;

/**
 * EmbeddingProviderManage —— embedding 注册表的**兼容 facade**（P5 实例化后保留）。
 *
 * <p>P5 起注册表是实例组件 {@link EmbeddingRegistry}（每个 {@code KernelRuntime} 一份）。
 * 本类静态方法全部委托默认运行时的注册表，存量调用点零改动。
 *
 * @deprecated P5：改用 {@code holder.getRuntime().embeddingRegistry()}。
 */
@Deprecated
public class EmbeddingProviderManage {

    private EmbeddingProviderManage() {
    }

    private static EmbeddingRegistry registry() {
        return KernelRuntime.defaultRuntime().embeddingRegistry();
    }

    /** 兼容入口：返回默认运行时的 embedding 注册表 */
    public static EmbeddingRegistry getInstance() {
        return registry();
    }

    public static void registerProvider(EmbeddingApiConfig config) {
        registry().registerProvider(config);
    }

    public static void registerProvider(String alias, EmbeddingProvider provider) {
        registry().registerProvider(alias, provider);
    }

    public static void unregisterProvider(String alias) {
        registry().unregisterProvider(alias);
    }

    public static EmbeddingProvider getProvider(String alias) {
        return registry().getProvider(alias);
    }

    public static EmbeddingProvider getProvider(String alias, boolean verify) {
        return registry().getProvider(alias, verify);
    }

    public static float[] embed(String alias, String text) {
        return registry().embed(alias, text);
    }

    public static float[] embed(String text) {
        return registry().embed(text);
    }

    public static void setDefaultProvider(String alias) {
        registry().setDefaultProvider(alias);
    }

    public static String getDefaultProvider() {
        return registry().getDefaultProvider();
    }

    public static Set<String> aliases() {
        return registry().aliases();
    }

    public static void reset(List<EmbeddingApiConfig> configs) {
        registry().reset(configs);
    }
}
