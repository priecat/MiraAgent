package net.itzq.mira.modules.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 声明导入/执行的校验报告（编排运行时协议 · resolve→validate 段产物）。
 *
 * <p>统一出口："缺什么、警告什么、是否致命"——导入端与运行端共用同一结构，
 * 前端可直接渲染为补全清单。绝不静默补默认值。
 */
public class ValidationReport {

    /** 缺失项：kind = model / tool / prompt / credential / invocation / config */
    public static class Missing {
        private final String kind;
        private final String ref;
        private final String detail;

        public Missing(String kind, String ref, String detail) {
            this.kind = kind;
            this.ref = ref;
            this.detail = detail;
        }

        public String getKind() {
            return kind;
        }

        public String getRef() {
            return ref;
        }

        public String getDetail() {
            return detail;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("ref", ref);
            m.put("detail", detail);
            return m;
        }
    }

    private final List<Missing> missing = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean fatal = false;

    public static ValidationReport ok() {
        return new ValidationReport();
    }

    /** 记一条缺失（kind/ref/detail） */
    public ValidationReport addMissing(String kind, String ref, String detail) {
        missing.add(new Missing(kind, ref, detail));
        return this;
    }

    /** 记一条警告（不阻断） */
    public ValidationReport addWarning(String warning) {
        if (warning != null && !warning.trim().isEmpty()) {
            warnings.add(warning);
        }
        return this;
    }

    /** 标记致命（导入/执行应中止） */
    public ValidationReport markFatal() {
        this.fatal = true;
        return this;
    }

    public List<Missing> getMissing() {
        return missing;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public boolean isFatal() {
        return fatal;
    }

    public boolean isEmpty() {
        return missing.isEmpty() && warnings.isEmpty();
    }

    /** 是否可继续：无致命且无缺失 */
    public boolean isPass() {
        return !fatal && missing.isEmpty();
    }

    /** 人类可读摘要（日志/接口返回） */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(fatal ? "[FATAL] " : (isPass() ? "[OK] " : "[INCOMPLETE] "));
        sb.append("missing=").append(missing.size()).append(", warnings=").append(warnings.size());
        for (Missing m : missing) {
            sb.append("\n  - ").append(m.getKind()).append(": ").append(m.getRef())
                    .append(m.getDetail() == null ? "" : " (" + m.getDetail() + ")");
        }
        for (String w : warnings) {
            sb.append("\n  ~ ").append(w);
        }
        return sb.toString();
    }

    /** 可序列化结构（接口返回） */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fatal", fatal);
        m.put("pass", isPass());
        List<Map<String, Object>> miss = new ArrayList<>();
        for (Missing x : missing) {
            miss.add(x.toMap());
        }
        m.put("missing", miss);
        m.put("warnings", new ArrayList<>(warnings));
        return m;
    }
}
