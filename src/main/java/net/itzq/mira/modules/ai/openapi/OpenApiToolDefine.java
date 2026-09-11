package net.itzq.mira.modules.ai.openapi;

import com.alibaba.fastjson2.JSONObject;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.tool.AiToolDefine;
import net.itzq.mira.modules.ai.client.tool.AiToolParam;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class OpenApiToolDefine implements AiToolDefine {

    private static final Method EXECUTE_METHOD;

    static {
        try {
            EXECUTE_METHOD = OpenApiInvoker.class.getMethod("execute",
                    JSONObject.class,
                    AgentContextHolder.class,
                    ApiOperation.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("无法获取 OpenApiToolExecutor.execute 方法", e);
        }
    }

    private final String name;
    private final String display;
    private final String description;
    private final List<AiToolParam> parameters;

    /** 对应的 API 操作定义（供注册到 OpenApiRegistry 使用） */
    private final ApiOperation operation;

    public OpenApiToolDefine(ApiOperation op) {
        this.operation = op;
        this.name = op.getName();
        this.display = op.getName();
        StringBuilder desc = new StringBuilder();
        if (op.getSummary() != null) {
            desc.append(op.getSummary());
        }
        if (op.getDescription() != null && !op.getDescription().isEmpty()) {
            if (desc.length() > 0) {
                desc.append("\n");
            }
            desc.append(op.getDescription());
        }
        this.description = desc.toString();
        this.parameters = toAiToolParams(op.getParams());
    }

    private static List<AiToolParam> toAiToolParams(List<ApiParam> params) {
        List<AiToolParam> list = new ArrayList<>();
        if (params == null) {
            return list;
        }
        for (ApiParam p : params) {
            list.add(AiToolParam.of(p.getName(), p.getDescription(), p.isRequired(), p.getType()));
        }
        return list;
    }

    public ApiOperation getOperation() {
        return operation;
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
