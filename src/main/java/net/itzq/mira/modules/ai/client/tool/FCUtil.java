package net.itzq.mira.modules.ai.client.tool;

import com.alibaba.fastjson2.JSONObject;
import io.github.classgraph.*;
import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.openai.tool.Tool;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
import net.itzq.mira.modules.ai.utils.JsonRepair;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 *  FCUtil
 *
 *  @author tangzq
 */
@Slf4j
public class FCUtil {

    // 移除了 Reflections 实例

    public static Map<String, Tool> toolEntityMap = new ConcurrentHashMap<>();

    public static Map<String, Method> toolMethodMap = new ConcurrentHashMap<>();

    /** 工具类实例缓存，避免每次调用都创建新实例 */
    private static final Map<Class<?>, Object> toolInstanceCache = new ConcurrentHashMap<>();

    static {
        log.info("===== 开始使用 ClassGraph 扫描 Tool 方法 =====");
        long startTime = System.currentTimeMillis();

        try (ScanResult scanResult = new ClassGraph()
                .enableClassInfo()
                .enableMethodInfo() // 必须启用方法信息扫描
                .enableAnnotationInfo()
                .scan()) {

            // 1. 获取所有包含带有 @Tool 注解方法的类
            ClassInfoList classInfoList = scanResult.getClassesWithMethodAnnotation(
                    net.itzq.mira.modules.ai.client.tool.annotation.Tool.class.getName());

            for (ClassInfo classInfo : classInfoList) {
                // 2. 遍历这些类的方法信息
                for (MethodInfo methodInfo : classInfo.getDeclaredMethodInfo()) {

                    // 3. 检查该方法是否确实带有 @Tool 注解
                    AnnotationInfo toolAnnotationInfo = methodInfo.getAnnotationInfo(
                            net.itzq.mira.modules.ai.client.tool.annotation.Tool.class.getName());

                    if (toolAnnotationInfo != null) {
                        try {
                            // 4. 将 ClassGraph 的 MethodInfo 转换为 Java 原生的 Method 对象
                            Method method = methodInfo.loadClassAndGetMethod();

                            // 获取原生的注解对象，方便读取 name() 等属性
                            net.itzq.mira.modules.ai.client.tool.annotation.Tool toolAnnotation =
                                    method.getAnnotation(net.itzq.mira.modules.ai.client.tool.annotation.Tool.class);

                            if (toolAnnotation != null) {
                                String functionName = toolAnnotation.name();
                                // 启动时直接缓存到 toolMethodMap
                                toolMethodMap.put(functionName, method);
                                log.debug("扫描并注册 Tool: {}", functionName);
                            }
                        } catch (Exception e) {
                            log.error("加载 Tool 方法失败: {}.{}", classInfo.getName(), methodInfo.getName(), e);
                        }
                    }
                }
            }

            long cost = System.currentTimeMillis() - startTime;
            log.info("===== ClassGraph 扫描完成，共注册 {} 个 Tool，耗时: {}ms =====", toolMethodMap.size(), cost);

        } catch (Exception e) {
            log.error("ClassGraph 扫描类路径失败", e);
        }
    }

    public static String invoke(String functionName, String argument, AgentContextHolder contextHolder) {

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
            // 原始参数解析失败，尝试修复
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
                response = "工具调用失败，错误信息：" + e.getMessage();
            }

            log.info("【FC End】 function：{}, argument：{} result：{}", functionName, argument, response);

            return response;
        } catch (Exception e) {
            log.error("调用方法失败", e);
            throw new RuntimeException("调用方法失败");
        }

    }

    public static List<Tool> getAllFunctionTools(List<String> functionList) {
        List<Tool> tools = new ArrayList<>();
        for (String functionName : functionList) {

            Tool tool = toolEntityMap.get(functionName);
            if (tool == null) {
                tool = getToolEntity(functionName);
            }
            if (tool != null) {
                toolEntityMap.put(functionName, tool);
                tools.add(tool);
            }

        }
        return !tools.isEmpty() ? tools : null;
    }

    public static Tool getTool(String functionName) {
        Tool tool = toolEntityMap.get(functionName);
        if (tool == null) {
            tool = getToolEntity(functionName);
        }
        if (tool != null) {
            toolEntityMap.put(functionName, tool);
        }
        return tool;
    }

    public static Tool getToolEntity(String functionName) {

        Tool.Function functionEntity = getFunctionEntity(functionName);
        if (functionEntity != null) {
            Tool tool = new Tool();
            tool.setType("function");
            tool.setFunction(functionEntity);
            return tool;
        }

        return null;
    }

    public static Tool.Function getFunctionEntity(String functionName) {

        // 优化：直接从启动时缓存的 toolMethodMap 中获取，不再需要实时扫描
        Method method = toolMethodMap.get(functionName);
        if (method == null) {
            return null;
        }

        net.itzq.mira.modules.ai.client.tool.annotation.Tool functionCall =
                method.getAnnotation(net.itzq.mira.modules.ai.client.tool.annotation.Tool.class);

        Tool.Function function = new Tool.Function();
        function.setName(functionCall.name());
        function.setSubAgent(functionCall.subAgent());
        function.setDisplay(functionCall.display());
        function.setDescription(functionCall.description());
        setFunctionParameters(function, method);

        return function;
    }

    private static void setFunctionParameters(Tool.Function function, Method method) {

        Map<String, Tool.Function.Property> parameters = new HashMap<>();
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

    /**
     * 将Java类型映射到JSON Schema数据类型
     */
    private static String mapJavaTypeToJsonSchemaType(Class<?> fieldType) {
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

    /**
     * 获取枚举类型的所有可能值
     */
    private static List<String> getEnumValues(Class<?> enumType) {
        List<String> enumValues = new ArrayList<>();
        for (Object enumConstant : enumType.getEnumConstants()) {
            enumValues.add(enumConstant.toString());
        }
        return enumValues;
    }

    public static List<String> preLoadAllTools() {

        List<String> fun = new ArrayList<>();

        // 优化：不再需要全量扫描反射，直接遍历启动时就已经缓存好的 toolMethodMap
        for (Map.Entry<String, Method> entry : toolMethodMap.entrySet()) {
            String currentFunctionName = entry.getKey();
            Method method = entry.getValue();

            net.itzq.mira.modules.ai.client.tool.annotation.Tool functionCall =
                    method.getAnnotation(net.itzq.mira.modules.ai.client.tool.annotation.Tool.class);

            Tool.Function function = new Tool.Function();
            function.setName(functionCall.name());
            function.setDisplay(functionCall.display());
            function.setSubAgent(functionCall.subAgent());
            function.setDescription(functionCall.description());
            setFunctionParameters(function, method);

            fun.add(currentFunctionName);
        }

        return fun;
    }


    /**
     * 从缓存获取 Function 实体（避免重复反射扫描）
     */
    private static Tool.Function getFunctionEntityFromCache(String functionName) {
        // 先从 toolEntityMap 中查找（已包含 Function 元数据）
        Tool cached = toolEntityMap.get(functionName);
        if (cached != null && cached.getFunction() != null) {
            return cached.getFunction();
        }
        // 缓存未命中，尝试获取并缓存
        Tool tool = getTool(functionName);
        return tool != null ? tool.getFunction() : null;
    }



    /**
     * 获取所有已注册的工具名称列表（包括禁用的）
     */
    public static List<String> getAllRegisteredToolNames() {
        return new ArrayList<>(toolMethodMap.keySet());
    }
}
