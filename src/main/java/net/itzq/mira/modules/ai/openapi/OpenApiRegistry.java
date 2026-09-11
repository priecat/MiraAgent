package net.itzq.mira.modules.ai.openapi;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class OpenApiRegistry {

    private static final Map<String, ApiOperation> REGISTRY = new ConcurrentHashMap<>();

    public static void register(ApiOperation op) {
        if (op != null && op.getName() != null) {
            REGISTRY.put(op.getName(), op);
        }
    }

    public static ApiOperation get(String name) {
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
