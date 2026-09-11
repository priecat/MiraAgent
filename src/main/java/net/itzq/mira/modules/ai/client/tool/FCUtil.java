package net.itzq.mira.modules.ai.client.tool;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.openai.tool.Tool;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
import net.itzq.mira.modules.ai.utils.JsonRepair;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

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

    static Reflections reflections =
            new Reflections(new ConfigurationBuilder().setUrls(ClasspathHelper.forJavaClassPath())
            .setScanners(Scanners.MethodsAnnotated, Scanners.TypesAnnotated));

    public static Map<String, Tool> toolEntityMap = new ConcurrentHashMap<>();

    public static Map<String, Method> toolMethodMap = new ConcurrentHashMap<>();

    public static String invoke(String functionName, String argument, AgentContextHolder contextHolder) {

        long currentTimeMillis = System.currentTimeMillis();
        log.info("【FC Begin】 function {}, argument {}", functionName, argument);

        Method method = toolMethodMap.get(functionName);
        try {
            String test = JsonRepair.autoFix(argument);
            if (test != null) {
                argument = test;
            }
        } catch (Exception e) {
            log.error("", e);
        }

        try {
            List<Object> invokeParams = new ArrayList<>();

            JSONObject args = JSONObject.parseObject(argument);

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
                Object invoke = method.invoke(method.getDeclaringClass().newInstance(),
                        invokeParams.toArray(new Object[] {}));

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

        Set<Method> methodsSet =
                reflections.getMethodsAnnotatedWith(net.itzq.mira.modules.ai.client.tool.annotation.Tool.class);

        for (Method method : methodsSet) {
            net.itzq.mira.modules.ai.client.tool.annotation.Tool functionCall =
                    method.getAnnotation(net.itzq.mira.modules.ai.client.tool.annotation.Tool.class);

            String currentFunctionName = functionCall.name();
            if (currentFunctionName.equals(functionName)) {
                Tool.Function function = new Tool.Function();
                function.setName(currentFunctionName);
                function.setSubAgent(functionCall.subAgent());
                function.setDisplay(functionCall.display());
                function.setDescription(functionCall.description());
                setFunctionParameters(function, method);

                toolMethodMap.put(functionName, method);
                return function;
            }

        }
        return null;
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

        Set<Method> methodsSet =
                reflections.getMethodsAnnotatedWith(net.itzq.mira.modules.ai.client.tool.annotation.Tool.class);

        for (Method method : methodsSet) {
            net.itzq.mira.modules.ai.client.tool.annotation.Tool functionCall =
                    method.getAnnotation(net.itzq.mira.modules.ai.client.tool.annotation.Tool.class);

            String currentFunctionName = functionCall.name();

            Tool.Function function = new Tool.Function();
            function.setName(currentFunctionName);
            function.setDisplay(functionCall.display());
            function.setSubAgent(functionCall.subAgent());
            function.setDescription(functionCall.description());
            setFunctionParameters(function, method);

            toolMethodMap.put(currentFunctionName, method);

            fun.add(currentFunctionName);
        }

        return fun;
    }
}
