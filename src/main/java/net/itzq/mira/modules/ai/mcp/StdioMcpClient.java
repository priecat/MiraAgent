package net.itzq.mira.modules.ai.mcp;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * stdio传输的MCP客户端
 * 通过ProcessBuilder启动子进程，通过stdin/stdout进行JSON-RPC通信
 */
@Slf4j
public class StdioMcpClient implements McpClient {

    private final McpServerConfig config;
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private final AtomicInteger requestId = new AtomicInteger(0);
    private volatile boolean connected = false;

    public StdioMcpClient(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public void connect() throws Exception {
        List<String> command = new ArrayList<>();
        command.add(config.getCommand());
        if (config.getArgs() != null) {
            command.addAll(config.getArgs());
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        // 合并环境变量
        Map<String, String> env = pb.environment();
        if (config.getEnv() != null) {
            env.putAll(config.getEnv());
        }
        pb.redirectErrorStream(false);

        log.info("启动MCP服务器进程: {}", config);
        process = pb.start();

        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), "UTF-8"));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));

        // 发送initialize请求
        JSONObject initParams = new JSONObject();
        JSONObject capabilities = new JSONObject();
        initParams.put("protocolVersion", "2024-11-05");
        initParams.put("capabilities", capabilities);
        initParams.put("clientInfo", new JSONObject()
                .fluentPut("name", "qai-agent")
                .fluentPut("version", "0.0.4"));

        JSONObject response = sendRequest("initialize", initParams);
        if (response != null) {
            log.info("MCP服务器 {} 初始化成功: {}", config.getName(),
                    response.getJSONObject("result") != null ?
                            response.getJSONObject("result").getJSONObject("serverInfo") : "unknown");
        }

        // 发送initialized通知
        sendNotification("notifications/initialized", new JSONObject());

        connected = true;
    }

    @Override
    public List<McpToolInfo> listTools() throws Exception {
        JSONObject response = sendRequest("tools/list", new JSONObject());
        List<McpToolInfo> tools = new ArrayList<>();

        if (response == null || response.getJSONObject("result") == null) {
            return tools;
        }

        JSONArray toolsArray = response.getJSONObject("result").getJSONArray("tools");
        if (toolsArray == null) {
            return tools;
        }

        for (int i = 0; i < toolsArray.size(); i++) {
            JSONObject tool = toolsArray.getJSONObject(i);
            String name = tool.getString("name");
            String desc = tool.getString("description");
            String schema = tool.getJSONObject("inputSchema") != null ?
                    tool.getJSONObject("inputSchema").toJSONString() : "{}";

            McpToolInfo toolInfo = new McpToolInfo(config.getName(), name, desc, schema);
            tools.add(toolInfo);
        }

        log.info("MCP服务器 {} 提供 {} 个工具", config.getName(), tools.size());
        return tools;
    }

    @Override
    public String callTool(String toolName, String arguments) throws Exception {
        JSONObject params = new JSONObject();
        params.put("name", toolName);

        // 解析参数
        if (arguments != null && !arguments.isEmpty()) {
            params.put("arguments", JSONObject.parseObject(arguments));
        } else {
            params.put("arguments", new JSONObject());
        }

        JSONObject response = sendRequest("tools/call", params);

        if (response == null) {
            return "工具调用失败: 无响应";
        }

        if (response.containsKey("error")) {
            JSONObject error = response.getJSONObject("error");
            return "工具调用错误: " + (error != null ? error.getString("message") : "unknown");
        }

        JSONObject result = response.getJSONObject("result");
        if (result == null) {
            return "工具调用返回空结果";
        }

        // 提取content中的文本
        JSONArray content = result.getJSONArray("content");
        if (content != null && !content.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.size(); i++) {
                JSONObject item = content.getJSONObject(i);
                String type = item.getString("type");
                if ("text".equals(type)) {
                    sb.append(item.getString("text"));
                } else {
                    sb.append(item.toJSONString());
                }
                if (i < content.size() - 1) {
                    sb.append("\n");
                }
            }
            return sb.toString();
        }

        return result.toJSONString();
    }

    @Override
    public void disconnect() {
        connected = false;
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (Exception e) {
            // ignore
        }
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (Exception e) {
            // ignore
        }
        if (process != null) {
            process.destroyForcibly();
        }
        log.info("MCP服务器 {} 已断开", config.getName());
    }

    @Override
    public boolean isConnected() {
        return connected && process != null && process.isAlive();
    }

    @Override
    public McpServerConfig getConfig() {
        return config;
    }

    /**
     * 发送JSON-RPC请求并等待响应
     */
    private JSONObject sendRequest(String method, JSONObject params) throws Exception {
        int id = requestId.incrementAndGet();
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.put("params", params);
        }

        String requestStr = request.toJSONString();
        log.debug("MCP发送: {}", requestStr);
        writer.write(requestStr);
        writer.write("\n");
        writer.flush();

        // 读取响应（跳过通知，匹配id）
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                JSONObject msg = JSONObject.parseObject(line);
                // 跳过通知（没有id的消息）
                if (!msg.containsKey("id")) {
                    continue;
                }
                if (msg.getIntValue("id") == id) {
                    log.debug("MCP接收: {}", msg.toJSONString());
                    return msg;
                }
            } catch (Exception e) {
                log.warn("解析MCP消息失败: {}", line, e);
            }
        }

        return null;
    }

    /**
     * 发送JSON-RPC通知（不等待响应）
     */
    private void sendNotification(String method, JSONObject params) throws Exception {
        JSONObject notification = new JSONObject();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.put("params", params);
        }

        writer.write(notification.toJSONString());
        writer.write("\n");
        writer.flush();
    }
}
