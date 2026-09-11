package net.itzq.mira.modules.ai.http;

import com.alibaba.fastjson2.JSONObject;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.tool.AiToolDefine;
import net.itzq.mira.modules.ai.tool.AiToolParam;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 HttpToolMeta 适配为统一的 AiToolDefine 工具定义，供 FCUtil 注册与调用。
 */
public class HttpToolDefine implements AiToolDefine {

    private static final Method EXECUTE_METHOD;

    static {
        try {
            EXECUTE_METHOD = HttpToolInvoker.class.getMethod("execute",
                    JSONObject.class,
                    AgentContextHolder.class,
                    HttpToolMeta.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("无法获取 HttpToolInvoker.execute 方法", e);
        }
    }

    private final String name;
    private final String display;
    private final String description;
    private final List<AiToolParam> parameters;

    /** 对应的 HTTP 工具元数据（供注册到 HttpToolRegistry 使用） */
    private final HttpToolMeta meta;

    public HttpToolDefine(HttpToolMeta meta) {
        this.meta = meta;
        this.name = meta.getName();
        this.display = meta.getName();
        StringBuilder desc = new StringBuilder();
        if (meta.getSummary() != null) {
            desc.append(meta.getSummary());
        }
        if (meta.getDescription() != null && !meta.getDescription().isEmpty()) {
            if (desc.length() > 0) {
                desc.append("\n");
            }
            desc.append(meta.getDescription());
        }
        this.description = desc.toString();
        this.parameters = toAiToolParams(meta.getParams());
    }

    private static List<AiToolParam> toAiToolParams(List<HttpParam> params) {
        List<AiToolParam> list = new ArrayList<>();
        if (params == null) {
            return list;
        }
        for (HttpParam p : params) {
            list.add(AiToolParam.of(p.getName(), p.getDescription(), p.isRequired(), p.typeClass()));
        }
        return list;
    }

    public HttpToolMeta getMeta() {
        return meta;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String display() {
        return display;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public List<AiToolParam> parameters() {
        return parameters;
    }

    @Override
    public Method executeMethod() {
        return EXECUTE_METHOD;
    }
}
