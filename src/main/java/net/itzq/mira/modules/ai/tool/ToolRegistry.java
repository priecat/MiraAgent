package net.itzq.mira.modules.ai.tool;

import com.alibaba.fastjson2.JSONObject;
import io.github.classgraph.*;
import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.openai.tool.Tool;
import net.itzq.mira.modules.ai.tool.annotation.ToolParam;
import net.itzq.mira.modules.ai.http.HttpToolMeta;
import net.itzq.mira.modules.ai.http.HttpToolRegistry;
import net.itzq.mira.modules.ai.utils.JsonRepair;
import net.itzq.mira.modules.toolfun.ToolFun;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表（编排运行时协议 · 实例组件）。
 *
 * <p>P5 实例化：原 {@link FCUtil} 的全部注册/查询/调用逻辑平移到本类，
 * 静态可变 Map 改为**实例私有**——每个内核运行时（KernelRuntime）拥有独立工具表，
 * 同进程多实例互不可见；{@link FCUtil} 降级为委托默认运行时的静态 facade（兼容期）。
 *
 * <p>行为差异（有意）：不再有静态初始化块隐式扫描——默认注册表的扫描由
 * {@code KernelRuntime.start()} 显式调用 {@link #initDefaultTools()} 完成，
 * 实例化顺序因此可控。
 */
@Slf4j
public class ToolRegistry {

    /** 来源：内核内置工具 */
    public static final String SOURCE_CORE = "core";

    /** 来源：未标注（向后兼容缺省值） */
    public static final String SOURCE_UNKNOWN = "app:unknown";

    private final Map<String, Tool> toolEntityMap = new ConcurrentHashMap<>();

    private final Map<String, Method> toolMethodMap = new ConcurrentHashMap<>();

    /**
     * 工具来源标注（声明域收归 · P1）：toolName → source。
     * source 形态：{@code core} / {@code app:<hostId>} / {@code plugin:<id>}。
     * 导出声明时按 source 分组生成 toolProviders，导入端据此判断缺哪些注册。
     */
    private final Map<String, String> toolSourceMap = new ConcurrentHashMap<>();

    /** 工具类实例缓存，避免每次调用都创建新实例 */
    private final Map<Class<?>, Object> toolInstanceCache = new ConcurrentHashMap<>();

    /**
     * 所属内核运行时（P5 多实例）：由 {@code KernelRuntime} 构造时反向绑定。
     * 注册表本就是 per-runtime 的实例组件——反向持有 owner 后，
     * "只有注册表、没有 holder"的调用方（如各 OpenAI 兼容 service）
     * 也能取到**本实例**的声明配置（SSE 超时等），而非默认运行时的。
     */
    private volatile net.itzq.mira.modules.runtime.KernelRuntime ownerRuntime;

    /** 反向绑定所属运行时（KernelRuntime 构造时调用一次） */
    public void bindRuntime(net.itzq.mira.modules.runtime.KernelRuntime runtime) {
        this.ownerRuntime = runtime;
    }

    /** 所属运行时（未绑定返回 null） */
    public net.itzq.mira.modules.runtime.KernelRuntime getRuntime() {
        return ownerRuntime;
    }

    /**
     * 扫描内核默认工具集（原静态初始化块的行为，现显式化）：
     * 由 {@code KernelRuntime.start()} 在装配早期调用一次。
     */
    public void initDefaultTools() {
        scanTools(ToolFun.defaultToolFun());
    }

    // ================================================================= 注册

    /** 注册自定义工具（来源标注缺省为 {@link #SOURCE_UNKNOWN}） */
    public synchronized void registerTool(AiToolDefine aiTool) {
        registerTool(aiTool, SOURCE_UNKNOWN);
    }

    /** 注册自定义工具并标注来源（声明域收归 · P1） */
    public synchronized void registerTool(AiToolDefine aiTool, String source) {
        String functionName = aiTool.name();

        if (toolMethodMap.containsKey(functionName)) {
            log.warn("工具函数名重复，将被覆盖: {} (类: {})", functionName, aiTool.getClass().getName());
        }

        Method method = aiTool.executeMethod();
        toolMethodMap.put(functionName, method);
        toolEntityMap.put(functionName, buildToolEntity(aiTool));
        toolSourceMap.put(functionName, source == null ? SOURCE_UNKNOWN : source);

        log.info("【AiTool】注册成功: {} -> {} (source={})",
                functionName, aiTool.getClass().getSimpleName(), source);
    }

    /** 手动扫描工具类（来源标注缺省 {@link #SOURCE_CORE}） */
    public void scanTools(Class<?>... toolClasses) {
        scanTools(SOURCE_CORE, toolClasses);
    }

    /** 手动扫描工具类并标注来源（声明域收归 · P1） */
    public void scanTools(String source, Class<?>... toolClasses) {
        if (toolClasses == null || toolClasses.length == 0) {
            log.warn("scanTools 未传入任何工具类，跳过扫描");
            return;
        }

        long startTime = System.currentTimeMillis();
        int registered = 0;

        for (Class<?> clazz : toolClasses) {
            if (clazz == null) {
                continue;
            }
            for (Method method : clazz.getDeclaredMethods()) {
                net.itzq.mira.modules.ai.tool.annotation.Tool toolAnnotation =
                        method.getAnnotation(net.itzq.mira.modules.ai.tool.annotation.Tool.class);
                if (toolAnnotation == null) {
                    continue;
                }
                String functionName = toolAnnotation.name();
                if (toolMethodMap.containsKey(functionName)) {
                    log.warn("工具函数名重复，将被覆盖: {} (类: {})", functionName, clazz.getName());
                }
                toolMethodMap.put(functionName, method);
                toolEntityMap.put(functionName, buildToolEntityFromMethod(method));
                toolSourceMap.put(functionName, source == null ? SOURCE_UNKNOWN : source);
                registered++;
                log.info("注册 Tool: {} (来自类: {}, source={})", functionName, clazz.getName(), source);
            }
        }

        long cost = System.currentTimeMillis() - startTime;
        log.info("===== 手动扫描完成，共注册 {} 个 Tool，耗时: {}ms =====", registered, cost);
    }

    /** 使用 ClassGraph 扫描整个类路径的 Tool 方法（可选能力，默认不用） */
    public void scanAllTools() {
        log.info("===== 开始使用 ClassGraph 扫描 Tool 方法 =====");
        long startTime = System.currentTimeMillis();

        try (ScanResult scanResult = new ClassGraph().enableClassInfo().enableMethodInfo()
                .enableAnnotationInfo().scan()) {

            ClassInfoList classInfoList = scanResult.getClassesWithMethodAnnotation(
                    net.itzq.mira.modules.ai.tool.annotation.Tool.class.getName());

            for (ClassInfo classInfo : classInfoList) {
                for (MethodInfo methodInfo : classInfo.getDeclaredMethodInfo()) {
                    AnnotationInfo toolAnnotationInfo = methodInfo.getAnnotationInfo(
                            net.itzq.mira.modules.ai.tool.annotation.Tool.class.getName());
                    if (toolAnnotationInfo != null) {
                        try {
                            Method method = methodInfo.loadClassAndGetMethod();
                            net.itzq.mira.modules.ai.tool.annotation.Tool toolAnnotation =
                                    method.getAnnotation(net.itzq.mira.modules.ai.tool.annotation.Tool.class);
                            if (toolAnnotation != null) {
                                String functionName = toolAnnotation.name();
                                toolMethodMap.put(functionName, method);
                                toolEntityMap.put(functionName, buildToolEntityFromMethod(method));
                                log.info("注册 Tool: {}", functionName);
                            }
                        } catch (Exception e) {
                            log.error("加载 Tool 方法失败: {}.{}", classInfo.getName(), methodInfo.getName(), e);
                        }
                    }
                }
            }

            long cost = System.currentTimeMillis() - startTime;
            log.info("===== ClassGraph 扫描完成，共注册 {} 个 Tool，耗时: {}ms =====",
                    toolMethodMap.size(), cost);
        } catch (Exception e) {
            log.error("ClassGraph 扫描类路径失败", e);
        }
    }

    /** 卸载单个工具 */
    public synchronized void unregisterTool(String functionName) {
        toolMethodMap.remove(functionName);
        toolEntityMap.remove(functionName);
        toolSourceMap.remove(functionName);
        log.info("【AiTool】卸载工具: {}", functionName);
    }

    /** 按前缀批量卸载工具（用于 HTTP 工具批量清理） */
    public synchronized void unregisterByPrefix(String prefix) {
        toolMethodMap.keySet().removeIf(k -> k != null && k.startsWith(prefix));
        toolEntityMap.keySet().removeIf(k -> k != null && k.startsWith(prefix));
        toolSourceMap.keySet().removeIf(k -> k != null && k.startsWith(prefix));
        log.info("【AiTool】按前缀卸载工具: {}", prefix);
    }

    /** 清空全部注册（{@code KernelRuntime.close()} 用：即用即释放，无残留） */
    public synchronized void clear() {
        int size = toolMethodMap.size();
        toolMethodMap.clear();
        toolEntityMap.clear();
        toolSourceMap.clear();
        toolInstanceCache.clear();
        log.info("【ToolRegistry】已清空 {} 个工具注册", size);
    }

    // ================================================================= 查询

    /** 工具来源快照（toolName → source），供声明导出分组 */
    public Map<String, String> getToolSources() {
        return new LinkedHashMap<>(toolSourceMap);
    }

    /** 按来源分组快照（source → 工具名清单），供声明导出生成 toolProviders */
    public Map<String, List<String>> groupBySource() {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : toolSourceMap.entrySet()) {
            String src = e.getValue() == null ? SOURCE_UNKNOWN : e.getValue();
            List<String> names = grouped.get(src);
            if (names == null) {
                names = new ArrayList<>();
                grouped.put(src, names);
            }
            names.add(e.getKey());
        }
        return grouped;
    }

    /** 工具实体（未注册返回 null） */
    public Tool getTool(String functionName) {
        return toolEntityMap.get(functionName);
    }

    /** 工具函数声明（未注册返回 null） */
    public Tool.Function getFunctionEntity(String functionName) {
        Tool tool = getTool(functionName);
        return tool != null ? tool.getFunction() : null;
    }

    /** 按名取一批工具实体（全空返回 null，与旧行为一致） */
    public List<Tool> getAllFunctionTools(List<String> functionList) {
        List<Tool> tools = new ArrayList<>();
        for (String functionName : functionList) {
            Tool tool = toolEntityMap.get(functionName);
            if (tool != null) {
                tools.add(tool);
            }
        }
        return !tools.isEmpty() ? tools : null;
    }

    /** 已注册全部工具名 */
    public List<String> getAllRegisteredToolNames() {
        return new ArrayList<>(toolMethodMap.keySet());
    }

    /** 已注册全部工具名（旧名 preLoadAllTools，语义相同） */
    public List<String> preLoadAllTools() {
        return getAllRegisteredToolNames();
    }

    /** 是否已注册该工具 */
    public boolean contains(String functionName) {
        return toolMethodMap.containsKey(functionName);
    }

    /** 已注册工具数量 */
    public int size() {
        return toolMethodMap.size();
    }

    // ================================================================= 调用

    /** 反射调用工具（参数解析失败会尝试 JsonRepair 修复，与旧 FCUtil.invoke 行为一致） */
    public String invoke(String functionName, String argument, AgentContextHolder contextHolder) {

        long currentTimeMillis = System.currentTimeMillis();
        log.info("【FC Begin】 function {}, argument {}", functionName, argument);

        Method method = toolMethodMap.get(functionName);
        if (method == null) {
            log.warn("【FC Error】工具未注册: {}", functionName);
            return "工具未注册: " + functionName;
        }

        JSONObject args = null;
        try {
            args = JSONObject.parseObject(argument);
        } catch (Exception directParseEx) {
            log.debug("原始参数解析失败，尝试 JsonRepair 修复: {}", directParseEx.getMessage());
            try {
                String fixed = JsonRepair.autoFix(argument);
                if (fixed != null) {
                    argument = fixed;
                }
                args = JSONObject.parseObject(argument);
            } catch (Exception repairEx) {
                log.error("参数修复后仍无法解析: {}", argument, repairEx);
                throw new RuntimeException("参数解析失败: " + repairEx.getMessage());
            }
        }

        try {
            List<Object> invokeParams = new ArrayList<>();

            Class<?>[] parameterTypes = method.getParameterTypes();
            Parameter[] parameters = method.getParameters();

            boolean isHttpTool = false;
            for (int i = 0; i < method.getParameterCount(); i++) {
                if (parameterTypes[i] == HttpToolMeta.class) {
                    isHttpTool = true;
                    break;
                }
            }

            if (isHttpTool) {
                HttpToolMeta httpToolMeta = HttpToolRegistry.get(functionName);
                invokeParams.add(args);
                invokeParams.add(contextHolder);
                invokeParams.add(httpToolMeta);
            } else {
                for (int i = 0; i < method.getParameterCount(); i++) {
                    Class<?> parameterType = parameterTypes[i];
                    Parameter parameter = parameters[i];

                    if (parameterType == AgentContextHolder.class) {
                        invokeParams.add(contextHolder);
                        continue;
                    }

                    ToolParam annotation = parameter.getAnnotation(ToolParam.class);
                    if (annotation != null) {
                        String key = parameter.getName();
                        Object object = args.getObject(key, parameterType);
                        invokeParams.add(object);
                    } else {
                        invokeParams.add(null);
                    }
                }
            }

            String response;
            try {
                Object toolInstance = toolInstanceCache.computeIfAbsent(
                        method.getDeclaringClass(),
                        cls -> {
                            try {
                                return cls.getDeclaredConstructor().newInstance();
                            } catch (Exception e) {
                                throw new RuntimeException("无法实例化工具类: " + cls.getName(), e);
                            }
                        }
                );
                Object invoke = method.invoke(toolInstance, invokeParams.toArray(new Object[] {}));
                response = com.alibaba.fastjson2.JSON.toJSONString(invoke);
            } catch (Exception e) {
                log.error("ERROR", e);
                // 统一协议 + 统一 JSON 序列化（前端可安全 parse；[mira:err] 前缀标识失败）
                Throwable cause = e.getCause() == null ? e : e.getCause();
                String detail = cause.getMessage() == null
                        ? cause.getClass().getSimpleName() : cause.getMessage();
                response = com.alibaba.fastjson2.JSON.toJSONString(
                        ToolCallResult.error(ToolCallResult.KERNEL_ERROR_PREFIX + detail));
            }

            log.info("【FC End】 function：{}, argument：{} result：{}", functionName, argument, response);
            return response;
        } catch (Exception e) {
            log.error("调用方法失败", e);
            throw new RuntimeException("调用方法失败");
        }
    }

    // ================================================================= Tool 实体构建

    private Tool buildToolEntity(AiToolDefine aiTool) {
        Tool.Function function = new Tool.Function();
        function.setName(aiTool.name());
        function.setDisplay(aiTool.display());
        function.setSubAgent(aiTool.subAgent());
        function.setDescription(aiTool.description());
        function.setParameters(buildParameters(aiTool.parameters()));
        Tool tool = new Tool();
        tool.setType("function");
        tool.setFunction(function);
        return tool;
    }

    private Tool.Function.Parameter buildParameters(List<AiToolParam> params) {
        Map<String, Tool.Function.Property> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (params != null) {
            for (AiToolParam p : params) {
                Tool.Function.Property prop = new Tool.Function.Property();
                prop.setType(mapJavaTypeToJsonSchemaType(p.getType()));
                prop.setDescription(p.getDescription());
                if (p.getType().isEnum()) {
                    prop.setEnumValues(getEnumValues(p.getType()));
                }
                properties.put(p.getName(), prop);
                if (p.isRequired()) {
                    required.add(p.getName());
                }
            }
        }
        return new Tool.Function.Parameter("object", properties, required);
    }

    /**
     * 根据方法上的 @Tool / @ToolParam 注解构建 Tool 实体。
     *
     * @param method 带有 @Tool 注解的方法
     * @return 构建好的 Tool 实体；方法无 @Tool 注解时返回 null
     */
    private Tool buildToolEntityFromMethod(Method method) {
        net.itzq.mira.modules.ai.tool.annotation.Tool toolAnnotation =
                method.getAnnotation(net.itzq.mira.modules.ai.tool.annotation.Tool.class);
        if (toolAnnotation == null) {
            return null;
        }
        Tool.Function function = new Tool.Function();
        function.setName(toolAnnotation.name());
        function.setSubAgent(toolAnnotation.subAgent());
        function.setDisplay(toolAnnotation.display());
        function.setDescription(toolAnnotation.description());
        setFunctionParameters(function, method);

        Tool tool = new Tool();
        tool.setType("function");
        tool.setFunction(function);
        return tool;
    }

    private void setFunctionParameters(Tool.Function function, Method method) {
        Map<String, Tool.Function.Property> parameters = new LinkedHashMap<>();
        List<String> requiredParameters = new ArrayList<>();

        for (int i = 0; i < method.getParameterCount(); i++) {
            String parameterName = method.getParameters()[i].getName();
            Type parameterType = method.getGenericParameterTypes()[i];

            Parameter parameter = method.getParameters()[i];
            ToolParam toolParamAnnotation = parameter.getAnnotation(ToolParam.class);
            if (toolParamAnnotation != null) {
                if (toolParamAnnotation.required()) {
                    requiredParameters.add(parameter.getName());
                }

                Class<?> fieldType = parameter.getType();
                String jsonType = mapJavaTypeToJsonSchemaType(fieldType);
                Tool.Function.Property property = new Tool.Function.Property();
                property.setType(jsonType);
                property.setDescription(toolParamAnnotation.description());
                if (fieldType.isEnum()) {
                    property.setEnumValues(getEnumValues(fieldType));
                }
                parameters.put(parameter.getName(), property);
            }
        }

        Tool.Function.Parameter parameter = new Tool.Function.Parameter("object", parameters, requiredParameters);
        function.setParameters(parameter);
    }

    /** 将 Java 类型映射到 JSON Schema 数据类型 */
    private String mapJavaTypeToJsonSchemaType(Class<?> fieldType) {
        if (fieldType.isEnum()) {
            return "string";
        } else if (fieldType.equals(String.class)) {
            return "string";
        } else if (fieldType.equals(int.class) || fieldType.equals(Integer.class) || fieldType.equals(long.class)
                || fieldType.equals(Long.class) || fieldType.equals(short.class) || fieldType.equals(Short.class)
                || fieldType.equals(float.class) || fieldType.equals(Float.class) || fieldType.equals(double.class)
                || fieldType.equals(Double.class)) {
            return "number";
        } else if (fieldType.equals(boolean.class) || fieldType.equals(Boolean.class)) {
            return "boolean";
        } else if (fieldType.isArray()) {
            return "array";
        } else if (Collection.class.isAssignableFrom(fieldType)) {
            return "array";
        } else if (Map.class.isAssignableFrom(fieldType)) {
            return "object";
        } else {
            return "object";
        }
    }

    /** 获取枚举类型的所有可能值 */
    private List<String> getEnumValues(Class<?> enumType) {
        List<String> enumValues = new ArrayList<>();
        for (Object enumConstant : enumType.getEnumConstants()) {
            enumValues.add(enumConstant.toString());
        }
        return enumValues;
    }
}
