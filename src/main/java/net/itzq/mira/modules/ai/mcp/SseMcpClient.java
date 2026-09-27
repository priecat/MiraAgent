package net.itzq.mira.modules.ai.mcp;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.core.utils.json.JsonArray;
import net.itzq.mira.core.utils.json.JsonObject;
import net.itzq.mira.core.utils.json.JsonUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE传输的MCP客户端
 * 通过HTTP POST发送JSON-RPC请求，通过SSE接收响应
 */
@Slf4j
public class SseMcpClient implements McpClient {

    private final McpServerConfig config;
    private final AtomicInteger requestId = new AtomicInteger(0);

    /** SSE事件中接收到的响应队列，按id索引 */
    private final Map<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();

    private String messagesEndpoint;
    private volatile boolean connected = false;
    private Thread sseThread;
    private HttpURLConnection sseConnection;

    public SseMcpClient(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public void connect() throws Exception {
        log.info("连接MCP SSE服务器: {}", config.getUrl());

        // 1. 建立SSE连接（使用标准HttpURLConnection）
        String sseUrl = config.getUrl();
        URL url = new URL(sseUrl);
        sseConnection = (HttpURLConnection) url.openConnection();
        sseConnection.setRequestMethod("GET");
        sseConnection.setRequestProperty("Accept", "text/event-stream");
        sseConnection.setConnectTimeout(10000);
        sseConnection.setReadTimeout(0); // 无超时，保持长连接

        int responseCode = sseConnection.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("SSE连接失败: HTTP " + responseCode);
        }

        // 2. 启动SSE监听线程
        final BufferedReader sseReader = new BufferedReader(
                new InputStreamReader(sseConnection.getInputStream(), "UTF-8"));

        sseThread = new Thread(() -> {
            try {
                String line;
                String eventType = null;
                StringBuilder data = new StringBuilder();

                while (!Thread.currentThread().isInterrupted()) {
                    line = sseReader.readLine();
                    if (line == null) {
                        break;
                    }

                    if (line.startsWith("event:")) {
                        eventType = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5).trim());
                    } else if (line.isEmpty() && data.length() > 0) {
                        handleSseEvent(eventType, data.toString());
                        eventType = null;
                        data = new StringBuilder();
                    }
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    log.warn("SSE监听线程异常: {}", e.getMessage());
                }
            }
        }, "MCP-SSE-" + config.getName());
        sseThread.setDaemon(true);
        sseThread.start();

        // 3. 等待endpoint事件
        Thread.sleep(500);
        if (messagesEndpoint == null) {
            String baseUrl = sseUrl.replaceAll("/sse$", "");
            messagesEndpoint = baseUrl + "/message";
            log.warn("未收到endpoint事件，使用默认消息端点: {}", messagesEndpoint);
        }

        // 4. 发送initialize请求
        JsonObject initParams = new JsonObject();
        initParams.put("protocolVersion", "2024-11-05");
        initParams.put("capabilities", new JsonObject());
        initParams.put("clientInfo", new JsonObject()
                .fluentPut("name", "qai-agent")
                .fluentPut("version", "0.0.4"));

        JsonObject response = sendRequest("initialize", initParams);
        if (response != null) {
            log.info("MCP SSE服务器 {} 初始化成功", config.getName());
        }

        // 5. 发送initialized通知
        sendNotification("notifications/initialized", new JsonObject());

        connected = true;
    }

    @Override
    public List<McpToolInfo> listTools() throws Exception {
        JsonObject response = sendRequest("tools/list", new JsonObject());
        List<McpToolInfo> tools = new ArrayList<>();

        if (response == null || response.getJsonObject("result") == null) {
            return tools;
        }

        JsonArray toolsArray = response.getJsonObject("result").getJsonArray("tools");
        if (toolsArray == null) {
            return tools;
        }

        for (int i = 0; i < toolsArray.size(); i++) {
            JsonObject tool = toolsArray.getJsonObject(i);
            String name = tool.getString("name");
            String desc = tool.getString("description");
            String schema = tool.getJsonObject("inputSchema") != null ?
                    tool.getJsonObject("inputSchema").toJSONString() : "{}";

            McpToolInfo toolInfo = new McpToolInfo(config.getName(), name, desc, schema);
            tools.add(toolInfo);
        }

        log.info("MCP SSE服务器 {} 提供 {} 个工具", config.getName(), tools.size());
        return tools;
    }

    @Override
    public String callTool(String toolName, String arguments) throws Exception {
        JsonObject params = new JsonObject();
        params.put("name", toolName);
        if (arguments != null && !arguments.isEmpty()) {
            params.put("arguments", JsonUtil.parseObject(arguments));
        } else {
            params.put("arguments", new JsonObject());
        }

        JsonObject response = sendRequest("tools/call", params);

        if (response == null) {
            return "工具调用失败: 无响应";
        }

        if (response.containsKey("error")) {
            JsonObject error = response.getJsonObject("error");
            return "工具调用错误: " + (error != null ? error.getString("message") : "unknown");
        }

        JsonObject result = response.getJsonObject("result");
        if (result == null) {
            return "工具调用返回空结果";
        }

        JsonArray content = result.getJsonArray("content");
        if (content != null && !content.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.size(); i++) {
                JsonObject item = content.getJsonObject(i);
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
        if (sseThread != null) {
            sseThread.interrupt();
        }
        if (sseConnection != null) {
            sseConnection.disconnect();
        }
        log.info("MCP SSE服务器 {} 已断开", config.getName());
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public McpServerConfig getConfig() {
        return config;
    }

    /**
     * 发送JSON-RPC请求（通过HTTP POST到messages endpoint）
     */
    private JsonObject sendRequest(String method, JsonObject params) throws Exception {
        int id = requestId.incrementAndGet();
        JsonObject request = new JsonObject();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.put("params", params);
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        // POST请求到messages endpoint
        URL url = new URL(messagesEndpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        OutputStream os = conn.getOutputStream();
        os.write(request.toJSONString().getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        conn.disconnect();

        if (code != 202 && code != 200) {
            pendingRequests.remove(id);
            log.error("MCP POST请求失败: HTTP {} ({})", code, method);
            return null;
        }

        // 等待SSE事件返回响应
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            log.error("MCP请求超时: method={}", method);
            return null;
        }
    }

    /**
     * 发送通知（不需要响应）
     */
    private void sendNotification(String method, JsonObject params) throws Exception {
        JsonObject notification = new JsonObject();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.put("params", params);
        }

        URL url = new URL(messagesEndpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        OutputStream os = conn.getOutputStream();
        os.write(notification.toJSONString().getBytes("UTF-8"));
        os.flush();
        os.close();

        conn.getResponseCode();
        conn.disconnect();
    }

    /**
     * 处理SSE事件
     */
    private void handleSseEvent(String eventType, String data) {
        if ("endpoint".equals(eventType)) {
            String baseUrl = config.getUrl().replaceAll("/sse$", "");
            if (data.startsWith("/")) {
                messagesEndpoint = baseUrl + data;
            } else {
                messagesEndpoint = data;
            }
            log.info("MCP SSE消息端点: {}", messagesEndpoint);
            return;
        }

        // 尝试解析为JSON-RPC响应
        try {
            JsonObject msg = JsonUtil.parseObject(data);
            if (msg.containsKey("id")) {
                int id = msg.getIntValue("id");
                CompletableFuture<JsonObject> future = pendingRequests.remove(id);
                if (future != null) {
                    future.complete(msg);
                }
            }
        } catch (Exception e) {
            log.debug("非JSON-RPC的SSE数据: {}", data);
        }
    }
}
