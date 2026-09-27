package net.itzq.mira.modules.ai.tool;

import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.openai.tool.Tool;
import net.itzq.mira.modules.runtime.KernelRuntime;

import java.util.List;
import java.util.Map;

/**
 * FCUtil —— 工具注册表的**兼容 facade**（P5 实例化后保留）。
 *
 * <p>P5 起工具注册表是实例组件 {@link ToolRegistry}（每个 {@code KernelRuntime} 一份，
 * 独立隔离）。本类的全部静态方法**委托默认运行时的注册表**，因此存量调用点零改动。
 *
 * <p>新代码请改用 {@code holder.getRuntime().toolRegistry()}（多实例场景必须如此，
 * 否则会写到默认实例上）。静态可变 Map 已移除——外部不再可能绕过注册表直写。
 *
 * @deprecated P5：迁移到 {@code holder.getRuntime().toolRegistry()}；本 facade 预期在
 *             宿主/插件调用点清理完毕后删除。
 */
@Deprecated
public class FCUtil {

    /** 来源：内核内置工具 */
    public static final String SOURCE_CORE = ToolRegistry.SOURCE_CORE;

    /** 来源：未标注（向后兼容缺省值） */
    public static final String SOURCE_UNKNOWN = ToolRegistry.SOURCE_UNKNOWN;

    private FCUtil() {
    }

    /** 默认运行时的工具注册表 */
    private static ToolRegistry registry() {
        return KernelRuntime.defaultRuntime().toolRegistry();
    }

    public static void registerTool(AiToolDefine aiTool) {
        registry().registerTool(aiTool);
    }

    public static void registerTool(AiToolDefine aiTool, String source) {
        registry().registerTool(aiTool, source);
    }

    public static void scanTools(Class<?>... toolClasses) {
        registry().scanTools(toolClasses);
    }

    public static void scanTools(String source, Class<?>... toolClasses) {
        registry().scanTools(source, toolClasses);
    }

    public static void scanAllTools() {
        registry().scanAllTools();
    }

    public static Map<String, String> getToolSources() {
        return registry().getToolSources();
    }

    public static Map<String, List<String>> groupBySource() {
        return registry().groupBySource();
    }

    public static String invoke(String functionName, String argument, AgentContextHolder contextHolder) {
        return registry().invoke(functionName, argument, contextHolder);
    }

    public static List<Tool> getAllFunctionTools(List<String> functionList) {
        return registry().getAllFunctionTools(functionList);
    }

    public static Tool getTool(String functionName) {
        return registry().getTool(functionName);
    }

    public static Tool.Function getFunctionEntity(String functionName) {
        return registry().getFunctionEntity(functionName);
    }

    public static List<String> preLoadAllTools() {
        return registry().preLoadAllTools();
    }

    public static List<String> getAllRegisteredToolNames() {
        return registry().getAllRegisteredToolNames();
    }

    public static void unregisterTool(String functionName) {
        registry().unregisterTool(functionName);
    }

    public static void unregisterByPrefix(String prefix) {
        registry().unregisterByPrefix(prefix);
    }
}
