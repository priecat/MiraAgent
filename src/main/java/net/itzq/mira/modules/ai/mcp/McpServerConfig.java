package net.itzq.mira.modules.ai.mcp;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP服务器配置实体
 * 支持两种transport: stdio (command + args) 和 sse (url)
 */
@Data
public class McpServerConfig {

    /** 服务器名称（key in mcpServers） */
    private String name;

    /** stdio模式: 可执行命令，如 npx/node/python */
    private String command;

    /** stdio模式: 命令参数 */
    private List<String> args = new ArrayList<>();

    /** stdio模式: 环境变量 */
    private Map<String, String> env = new HashMap<>();

    /** sse模式: 服务器URL，如 http://localhost:3001/sse */
    private String url;

    /** 传输类型: stdio 或 sse */
    private String transport;

    /**
     * 判断是否为stdio传输
     */
    public boolean isStdio() {
        return command != null && !command.isEmpty();
    }

    /**
     * 判断是否为SSE传输
     */
    public boolean isSse() {
        return url != null && !url.isEmpty();
    }

    /**
     * 从JSON对象解析配置
     */
    public static McpServerConfig fromJson(String name, com.alibaba.fastjson2.JSONObject json) {
        McpServerConfig config = new McpServerConfig();
        config.setName(name);

        // stdio模式
        config.setCommand(json.getString("command"));
        if (json.containsKey("args")) {
            config.setArgs(json.getJSONArray("args").toJavaList(String.class));
        }
        if (json.containsKey("env")) {
            com.alibaba.fastjson2.JSONObject envObj = json.getJSONObject("env");
            Map<String, String> envMap = new HashMap<>();
            for (String key : envObj.keySet()) {
                envMap.put(key, envObj.getString(key));
            }
            config.setEnv(envMap);
        }

        // sse模式
        config.setUrl(json.getString("url"));

        // 确定传输类型
        if (config.isStdio()) {
            config.setTransport("stdio");
        } else if (config.isSse()) {
            config.setTransport("sse");
        }

        return config;
    }

    @Override
    public String toString() {
        if (isStdio()) {
            return name + " (stdio: " + command + " " + String.join(" ", args) + ")";
        } else if (isSse()) {
            return name + " (sse: " + url + ")";
        }
        return name + " (unknown)";
    }
}
