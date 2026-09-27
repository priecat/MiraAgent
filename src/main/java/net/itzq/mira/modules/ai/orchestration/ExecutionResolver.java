package net.itzq.mira.modules.ai.orchestration;

import net.itzq.mira.modules.ai.client.config.ModelApiConfig;
import net.itzq.mira.modules.config.GlobalConfigManager;
import net.itzq.mira.modules.config.ValidationReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 执行解析器（编排运行时协议 · resolve → validate 两段）。
 *
 * <pre>
 * resolve(blueprint, invocation)
 *   refs.model      → 声明里是否存在该 alias（或 @invocation.modelAlias 间接引用）
 *   refs.toolkits   → 经宿主 ToolResolver 解析为工具名白名单（fail-closed）
 *   requires[]      → 调用参数键存在性
 *   → Resolution { report, resolvedRefs, modelAlias, toolWhitelist }
 * </pre>
 *
 * <p>设计要点：**执行前的报告而非执行中的报错**——插件节点不再等模型调用失败才知道
 * 模型没声明/工具包不存在。声明（模型）由内核 {@link GlobalConfigManager} 直读；
 * 工具包解析依赖宿主持久化（ToolkitStore），经 {@link ToolResolver} 回调注入，
 * 内核因此对宿主零依赖。
 */
public final class ExecutionResolver {

    private ExecutionResolver() {
    }

    /** 工具包解析回调*/
    public interface ToolResolver {
        Set<String> resolve(List<?> kitIds);
    }

    /** 解析结果 */
    public static class Resolution {
        private final ValidationReport report;
        private final Map<String, Object> resolvedRefs;
        private final String modelAlias;
        private final Set<String> toolWhitelist;

        Resolution(ValidationReport report, Map<String, Object> resolvedRefs,
                   String modelAlias, Set<String> toolWhitelist) {
            this.report = report;
            this.resolvedRefs = resolvedRefs;
            this.modelAlias = modelAlias;
            this.toolWhitelist = toolWhitelist;
        }

        public ValidationReport getReport() {
            return report;
        }

        public Map<String, Object> getResolvedRefs() {
            return resolvedRefs;
        }

        /** 解析后的模型 alias（null = 未声明/无默认） */
        public String getModelAlias() {
            return modelAlias;
        }

        /** 解析后的工具白名单（已含 sys 基线，由调用方决定是否并入） */
        public Set<String> getToolWhitelist() {
            return toolWhitelist;
        }

        /** 可继续执行：无致命且无缺失 */
        public boolean canExecute() {
            return report.isPass();
        }
    }

    /** 简化入口：无工具包解析需求时传 null resolver。 */
    public static Resolution resolve(NodeBlueprint blueprint, Invocation invocation) {
        return resolve(blueprint, invocation, null);
    }

    /**
     * 按**指定声明**解析（多实例 /声明导入端用）：不读默认运行时的声明。
     *
     * @param declaration 用于校验模型 alias 的声明实例（null → 回落默认运行时声明）
     */
    public static Resolution resolve(NodeBlueprint blueprint, Invocation invocation,
                                     ToolResolver toolResolver, GlobalConfigManager declaration) {
        NodeBlueprint bp = blueprint == null ? new NodeBlueprint() : blueprint;
        Invocation inv = invocation == null ? Invocation.builder().build() : invocation;
        ValidationReport report = ValidationReport.ok();
        Map<String, Object> resolved = new LinkedHashMap<>();
        Set<String> tools = new LinkedHashSet<>();
        String modelAlias = null;

        for (Map.Entry<String, Object> e : bp.getRefs().entrySet()) {
            String name = e.getKey();
            Object value = resolveIndirect(e.getValue(), inv, report, name);
            if ("model".equals(name)) {
                modelAlias = value == null ? null : String.valueOf(value);
                if (modelAlias == null || modelAlias.trim().isEmpty()) {
                    String def = defaultModelAlias(declaration);
                    if (def == null) {
                        report.addMissing("model", "(未声明)", "blueprint 未指定模型且声明里无 defaultModel");
                    } else {
                        modelAlias = def;
                        report.addWarning("blueprint 未指定模型，回落声明默认模型: " + def);
                    }
                } else if (!hasModel(declaration, modelAlias)) {
                    report.addMissing("model", modelAlias, "声明（declaration.models）中不存在该 alias");
                }
                resolved.put(name, modelAlias);
                continue;
            }
            if ("toolkits".equals(name)) {
                if (value instanceof List && !((List<?>) value).isEmpty()) {
                    if (toolResolver == null) {
                        report.addWarning("toolkits 未解析（无 ToolResolver）: " + value);
                    } else {
                        try {
                            Set<String> names = toolResolver.resolve((List<?>) value);
                            if (names != null) {
                                tools.addAll(names);
                            }
                        } catch (Exception ex) {
                            report.addMissing("toolkit", String.valueOf(value),
                                    ex.getMessage() == null ? "工具包解析失败" : ex.getMessage());
                        }
                    }
                }
                resolved.put(name, value);
                continue;
            }
            resolved.put(name, value);
        }

        for (String req : bp.getRequires()) {
            String key = normalizeRequireKey(req);
            if (!inv.has(key)) {
                report.addMissing("invocation", req, "调用参数缺少该键（invocation." + key + "）");
            }
        }
        return new Resolution(report, resolved, modelAlias, tools);
    }

    /**
     * 解析 blueprint × invocation → 有效执行配置。
     *
     * @param blueprint     节点 blueprint（可 null → 视为空 blueprint）
     * @param invocation    调用参数（可 null → 视为空 invocation）
     * @param toolResolver  工具包解析回调（可 null：refs.toolkits 非空时记 warning）
     */
    public static Resolution resolve(NodeBlueprint blueprint, Invocation invocation,
                                     ToolResolver toolResolver) {
        NodeBlueprint bp = blueprint == null ? new NodeBlueprint() : blueprint;
        Invocation inv = invocation == null ? Invocation.builder().build() : invocation;
        ValidationReport report = ValidationReport.ok();
        Map<String, Object> resolved = new LinkedHashMap<>();
        Set<String> tools = new LinkedHashSet<>();
        String modelAlias = null;

        // ---- 1) refs：按名引用声明（支持 @invocation.* 间接引用） ----
        for (Map.Entry<String, Object> e : bp.getRefs().entrySet()) {
            String name = e.getKey();
            Object raw = e.getValue();
            Object value = resolveIndirect(raw, inv, report, name);

            if ("model".equals(name)) {
                modelAlias = value == null ? null : String.valueOf(value);
                boolean declared = hasModel(modelAlias);
                if (modelAlias == null || modelAlias.trim().isEmpty()) {
                    String def = defaultModelAlias();
                    if (def == null) {
                        report.addMissing("model", "(未声明)", "blueprint 未指定模型且声明里无 defaultModel");
                    } else {
                        modelAlias = def;
                        report.addWarning("blueprint 未指定模型，回落声明默认模型: " + def);
                    }
                } else if (!declared) {
                    report.addMissing("model", modelAlias, "声明（declaration.models）中不存在该 alias");
                }
                resolved.put(name, modelAlias);
                continue;
            }

            if ("toolkits".equals(name)) {
                if (value instanceof List && !((List<?>) value).isEmpty()) {
                    if (toolResolver == null) {
                        report.addWarning("toolkits 未解析（无 ToolResolver）: " + value);
                    } else {
                        try {
                            Set<String> names = toolResolver.resolve((List<?>) value);
                            if (names != null) {
                                tools.addAll(names);
                            }
                        } catch (Exception ex) {
                            report.addMissing("toolkit", String.valueOf(value),
                                    ex.getMessage() == null ? "工具包解析失败" : ex.getMessage());
                        }
                    }
                }
                resolved.put(name, value);
                continue;
            }

            resolved.put(name, value);
        }

        // ---- 2) requires：调用参数键存在性 ----
        for (String req : bp.getRequires()) {
            String key = normalizeRequireKey(req);
            if (!inv.has(key)) {
                report.addMissing("invocation", req, "调用参数缺少该键（invocation." + key + "）");
            }
        }

        return new Resolution(report, resolved, modelAlias, tools);
    }

    /** {@code @invocation.key} → 运行时取调用参数；否则原样返回 */
    private static Object resolveIndirect(Object raw, Invocation inv,
                                          ValidationReport report, String refName) {
        if (!(raw instanceof String)) {
            return raw;
        }
        String s = (String) raw;
        if (!s.startsWith("@invocation.")) {
            return raw;
        }
        String key = s.substring("@invocation.".length());
        Object v = inv.get(key);
        if (v == null) {
            report.addMissing("invocation", s,
                    "refs." + refName + " 间接引用的调用参数不存在");
            return null;
        }
        return v;
    }

    /** {@code invocation.question} / {@code question} 两种写法都接受 */
    private static String normalizeRequireKey(String req) {
        if (req == null) {
            return "";
        }
        String k = req.trim();
        return k.startsWith("invocation.") ? k.substring("invocation.".length()) : k;
    }

    /** 声明里是否存在该模型 alias */
    /** 声明里是否存在该模型 alias（默认运行时声明） */
    public static boolean hasModel(String alias) {
        return hasModel(null, alias);
    }

    /** 声明里是否存在该模型 alias（指定声明实例；null → 默认运行时） */
    public static boolean hasModel(GlobalConfigManager declaration, String alias) {
        if (alias == null || alias.trim().isEmpty()) {
            return false;
        }
        try {
            GlobalConfigManager decl = declaration != null
                    ? declaration : GlobalConfigManager.config();
            List<ModelApiConfig> models = decl.getModels();
            if (models == null) {
                return false;
            }
            for (ModelApiConfig m : models) {
                if (m != null && alias.trim().equals(m.getAlias())) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /** 声明里的默认模型 alias（无则 null） */
    public static String defaultModelAlias() {
        return defaultModelAlias(null);
    }

    /** 声明里的默认模型 alias（指定声明实例；null → 默认运行时） */
    public static String defaultModelAlias(GlobalConfigManager declaration) {
        try {
            GlobalConfigManager decl = declaration != null
                    ? declaration : GlobalConfigManager.config();
            if (decl.getAgentConfig() == null) {
                return null;
            }
            String def = decl.getAgentConfig().getDefaultModel();
            return (def == null || def.trim().isEmpty()) ? null : def.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** 便捷：把 Resolution 报告渲染成"缺什么"的可读清单（日志/接口） */
    public static List<Map<String, Object>> missingList(Resolution r) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (r != null && r.getReport() != null) {
            for (ValidationReport.Missing m : r.getReport().getMissing()) {
                list.add(m.toMap());
            }
        }
        return list;
    }
}
