package net.itzq.mira.modules.ai.client.handle;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StreamEventHandler 处理器管理
 *
 * @author tangzq
 */
@Slf4j
public class StreamEventHandlerManage {

    private static volatile StreamEventHandlerManage instance;

    /**
     * 别名 -> 处理器 Class 的白名单。
     * 仅存放 {@link StreamEventHandler} 的子类，且必须由可信代码注册
     */
    private final Map<String, Class<? extends StreamEventHandler>> handlers = new ConcurrentHashMap<>();

    /** 默认处理器别名 */
    private static volatile String defaultHandler = "default";

    private StreamEventHandlerManage() {
        // 内置注册
        registerBuiltin(OpenAICompatibleStreamEventHandler.class);
        // 别名
        registerHandler(defaultHandler, OpenAICompatibleStreamEventHandler.class);
    }

    public static StreamEventHandlerManage getInstance() {
        if (instance == null) {
            synchronized (StreamEventHandlerManage.class) {
                if (instance == null) {
                    instance = new StreamEventHandlerManage();
                }
            }
        }
        return instance;
    }

    /**
     * 注册指定别名的处理器类（带构造参数 SseEventListener 的子类）
     *
     * @param alias 别名（配置中引用的标识，不可为空）
     * @param clazz 处理器类（必须是 StreamEventHandler 的子类，不可为空）
     */
    public void registerHandler(String alias, Class<? extends StreamEventHandler> clazz) {
        if (alias == null || alias.isEmpty()) {
            throw new IllegalArgumentException("alias 不能为空");
        }
        if (clazz == null) {
            throw new IllegalArgumentException("handlerClass 不能为空");
        }
        handlers.put(alias, clazz);
    }

    /**
     * 注册内置处理器：自动以「全限定名」别名登记
     *
     * @param clazz 处理器类
     */
    public void registerBuiltin(Class<? extends StreamEventHandler> clazz) {
        if (clazz == null) {
            throw new IllegalArgumentException("handlerClass 不能为空");
        }
        handlers.put(clazz.getName(), clazz);
    }

    /** 移除指定别名 */
    public void unregisterHandler(String alias) {
        handlers.remove(alias);
    }

    /**
     * 通过别名/类名安全解析处理器类。
     *
     * <p><b>安全约束</b>：仅从白名单中查找，绝不对未知字符串调用 {@code Class.forName}。
     * 未注册的输入一律按异常或 null 处理，杜绝反序列化后任意类加载。</p>
     *
     * @param key 别名、简单类名或全限定名
     * @return 处理器类（verify=false 且未命中时返回 null）
     */
    public static Class<? extends StreamEventHandler> resolve(String key) {
        return resolve(key, true);
    }

    public static Class<? extends StreamEventHandler> resolve(String key, boolean verify) {
        if (key == null || key.isEmpty()) {
            if (verify) {
                throw new RuntimeException("StreamEventHandler 别名不能为空");
            }
            return null;
        }
        Class<? extends StreamEventHandler> clazz = getInstance().handlers.get(key);
        if (clazz == null && verify) {
            throw new RuntimeException(
                    "StreamEventHandler 未注册: " + key
                            + "，请先通过 registerHandler 注册或使用内置别名");
        }
        if (clazz == null) {
            log.warn("StreamEventHandler 未注册：{}", key);
        }
        return clazz;
    }

    /**
     * 按别名解析并实例化处理器（使用 SseEventListener 单参构造）。
     *
     * @param key     别名
     * @param listener 流式事件监听器
     * @return 处理器实例
     */
    public static StreamEventHandler instantiate(String key, SseEventListener listener) {
        Class<? extends StreamEventHandler> clazz = resolve(key);
        try {
            return clazz.getConstructor(SseEventListener.class).newInstance(listener);
        } catch (Exception e) {
            throw new RuntimeException("实例化 StreamEventHandler 失败: " + key, e);
        }
    }

    public static String getDefaultHandler() {
        return defaultHandler;
    }

    public void setDefaultHandler(String alias) {
        defaultHandler = alias;
    }

    /** 获取所有已注册的别名 */
    public Set<String> aliases() {
        return handlers.keySet();
    }

    public static void reset() {
        getInstance().handlers.clear();
    }
}
