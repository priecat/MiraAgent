package net.itzq.mira.modules.ai.http;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP 工具元数据注册表（内存态，进程重启后需重新注册）。
 */
public class HttpToolRegistry {

    private static final Map<String, HttpToolMeta> REGISTRY = new ConcurrentHashMap<>();

    public static void register(HttpToolMeta meta) {
        if (meta != null && meta.getName() != null) {
            REGISTRY.put(meta.getName(), meta);
        }
    }

    public static HttpToolMeta get(String name) {
        return REGISTRY.get(name);
    }

    public static void unregister(String name) {
        REGISTRY.remove(name);
    }

    public static void unregisterByPrefix(String prefix) {
        REGISTRY.keySet().removeIf(k -> k != null && k.startsWith(prefix));
    }

    public static Set<String> names() {
        return REGISTRY.keySet();
    }
}
