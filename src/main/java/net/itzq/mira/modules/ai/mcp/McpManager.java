package net.itzq.mira.modules.ai.mcp;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP管理器，单例模式
 * 管理所有MCP服务器连接、工具发现和调用路由
 */
@Slf4j
public class McpManager {

    private static final McpManager INSTANCE = new McpManager();

    /** MCP客户端注册表 serverName -> McpClient */
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();

    /** 工具索引 fullToolName -> McpToolInfo */
    private final Map<String, McpToolInfo> toolIndex = new ConcurrentHashMap<>();

    /** 工具名到服务器名映射 toolFullName -> serverName */
    private final Map<String, String> toolServerMap = new ConcurrentHashMap<>();

    private volatile boolean initialized = false;

    private McpManager() {
    }

    public static McpManager getInstance() {
        return INSTANCE;
    }

    /**
     * 从标准MCP配置JSON初始化
     * 格式: {"mcpServers": {"server1": {...}, "server2": {...}}}
     */
    public synchronized void init(String configJson) {
        if (initialized) {
            return;
        }
        if (StringUtils.isBlank(configJson)) {
            log.info("MCP配置为空，跳过初始化");
            initialized = true;
            return;
        }

        try {
            JSONObject config = JSONObject.parseObject(configJson);
            JSONObject servers = config.getJSONObject("mcpServers");
            if (servers == null || servers.isEmpty()) {
                log.info("mcpServers配置为空");
                initialized = true;
                return;
            }

            for (String serverName : servers.keySet()) {
                JSONObject serverConfig = servers.getJSONObject(serverName);
                registerServer(serverName, serverConfig);
            }

        } catch (Exception e) {
            log.error("解析MCP配置失败", e);
        }

        initialized = true;
        log.info("McpManager 初始化完成，共连接 {} 个服务器，发现 {} 个工具",
                clients.size(), toolIndex.size());
    }

    /**
     * 注册单个MCP服务器
     */
    public void registerServer(String serverName, JSONObject serverConfig) {
        try {
            McpServerConfig config = McpServerConfig.fromJson(serverName, serverConfig);

            McpClient client;
            if (config.isStdio()) {
                client = new StdioMcpClient(config);
            } else if (config.isSse()) {
                client = new SseMcpClient(config);
            } else {
                log.warn("MCP服务器 {} 配置无效，缺少command或url", serverName);
                return;
            }

            // 连接并发现工具
            client.connect();
            List<McpToolInfo> tools = client.listTools();

            clients.put(serverName, client);
            for (McpToolInfo tool : tools) {
                toolIndex.put(tool.getFullName(), tool);
                toolServerMap.put(tool.getFullName(), serverName);
            }

            log.info("MCP服务器 {} 已注册，提供 {} 个工具", serverName, tools.size());

        } catch (Exception e) {
            log.error("注册MCP服务器 {} 失败", serverName, e);
        }
    }

    /**
     * 调用MCP工具
     *
     * @param fullToolName 完整工具名 mcp__serverName__toolName
     * @param arguments    参数JSON字符串
     */
    public String callTool(String fullToolName, String arguments) {
        String serverName = toolServerMap.get(fullToolName);
        if (serverName == null) {
            return "MCP工具不存在: " + fullToolName;
        }

        McpClient client = clients.get(serverName);
        if (client == null || !client.isConnected()) {
            return "MCP服务器未连接: " + serverName;
        }

        McpToolInfo toolInfo = toolIndex.get(fullToolName);
        if (toolInfo == null) {
            return "MCP工具不存在: " + fullToolName;
        }

        try {
            log.info("调用MCP工具: {} (server: {})", toolInfo.getName(), serverName);
            return client.callTool(toolInfo.getName(), arguments);
        } catch (Exception e) {
            log.error("调用MCP工具失败: {}", fullToolName, e);
            return "MCP工具调用失败: " + e.getMessage();
        }
    }

    /**
     * 获取所有已发现的MCP工具
     */
    public Collection<McpToolInfo> getAllTools() {
        return toolIndex.values();
    }

    /**
     * 生成MCP工具摘要列表（用于系统提示词）
     */
    public String buildToolsSummary() {
        if (toolIndex.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpToolInfo tool : toolIndex.values()) {
            sb.append(tool.toSummary()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 获取所有已连接的服务器
     */
    public Collection<McpClient> getClients() {
        return clients.values();
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 是否有可用的MCP工具
     */
    public boolean hasTools() {
        return !toolIndex.isEmpty();
    }

    /**
     * 关闭所有连接
     */
    public void shutdown() {
        for (McpClient client : clients.values()) {
            try {
                client.disconnect();
            } catch (Exception e) {
                log.warn("关闭MCP客户端异常", e);
            }
        }
        clients.clear();
        toolIndex.clear();
        toolServerMap.clear();
        initialized = false;
    }
}
