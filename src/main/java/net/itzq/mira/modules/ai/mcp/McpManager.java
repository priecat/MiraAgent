package net.itzq.mira.modules.ai.mcp;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP管理器，静态工具类
 * 无状态：每次调用时建立连接，调用完立即关闭，不池化
 */
@Slf4j
public class McpManager {

    /**
     * 预解析：连接 -> listTools -> 断开，返回 configJson + 工具信息
     * 调用方在构建 context 前调用
     *
     * @param configJson MCP配置JSON，格式: {"mcpServers": {"server1": {...}, ...}}
     * @return 预解析结果
     */
    public static McpPrepared prepare(String configJson) {
        List<McpToolInfo> tools = new ArrayList<>();
        if (StringUtils.isBlank(configJson)) {
            return new McpPrepared(configJson, tools);
        }

        try {
            JSONObject config = JSONObject.parseObject(configJson);
            JSONObject servers = config.getJSONObject("mcpServers");
            if (servers == null || servers.isEmpty()) {
                return new McpPrepared(configJson, tools);
            }

            for (String serverName : servers.keySet()) {
                JSONObject serverConfig = servers.getJSONObject(serverName);
                try {
                    List<McpToolInfo> serverTools = discoverServerTools(serverName, serverConfig);
                    tools.addAll(serverTools);
                } catch (Exception e) {
                    log.error("连接MCP服务器失败: {}", serverName, e);
                }
            }
        } catch (Exception e) {
            log.error("解析MCP配置失败", e);
        }

        return new McpPrepared(configJson, tools);
    }

    /**
     * 连接单个服务器 -> listTools -> 断开
     */
    private static List<McpToolInfo> discoverServerTools(String serverName, JSONObject serverConfig) throws Exception {
        McpServerConfig config = McpServerConfig.fromJson(serverName, serverConfig);
        McpClient client = createClient(config);
        try {
            client.connect();
            List<McpToolInfo> tools = client.listTools();
            log.info("MCP服务器 {} 已发现 {} 个工具", serverName, tools.size());
            return tools;
        } finally {
            client.disconnect();
        }
    }

    /**
     * 运行时调用：从 configJson 找到 server -> 连接 -> callTool -> 断开
     *
     * @param configJson    MCP配置JSON
     * @param toolFullName  完整工具名 mcp__{serverName}__{toolName}
     * @param arguments     参数JSON字符串
     * @return 调用结果
     */
    public static String callTool(String configJson, String toolFullName, String arguments) {
        if (StringUtils.isBlank(configJson)) {
            return "MCP配置为空";
        }

        // 解析 toolFullName: mcp__{serverName}__{toolName}
        String[] parts = toolFullName.split("__", 3);
        if (parts.length < 3) {
            return "无效的MCP工具名格式: " + toolFullName;
        }
        String serverName = parts[1];
        String toolName = parts[2];

        try {
            JSONObject config = JSONObject.parseObject(configJson);
            JSONObject servers = config.getJSONObject("mcpServers");
            if (servers == null || !servers.containsKey(serverName)) {
                return "MCP服务器未配置: " + serverName;
            }

            McpServerConfig serverConfig = McpServerConfig.fromJson(serverName, servers.getJSONObject(serverName));
            McpClient client = createClient(serverConfig);
            try {
                client.connect();
                log.info("调用MCP工具: {} (server: {})", toolName, serverName);
                return client.callTool(toolName, arguments);
            } finally {
                client.disconnect();
            }
        } catch (Exception e) {
            log.error("调用MCP工具失败: {}", toolFullName, e);
            return "MCP工具调用失败: " + e.getMessage();
        }
    }

    /**
     * 根据配置创建客户端
     */
    private static McpClient createClient(McpServerConfig config) {
        if (config.isStdio()) {
            return new StdioMcpClient(config);
        } else if (config.isSse()) {
            return new SseMcpClient(config);
        }
        throw new IllegalArgumentException("无效的MCP服务器配置: " + config.getName());
    }
}
