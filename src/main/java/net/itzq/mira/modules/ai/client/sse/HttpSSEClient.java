package net.itzq.mira.modules.ai.client.sse;

import cn.hutool.core.io.resource.BytesResource;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.client.handle.HttpStreamEventInterface;
import net.itzq.mira.modules.config.GlobalConfigManager;
import net.itzq.mira.modules.config.SseClientConfig;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * HttpSSEClient -   SSE 客户端
 *
 * @author tangzq
 */
@Slf4j
public class HttpSSEClient {

    private static volatile HttpSSEClient instance;
    private static final Object LOCK = new Object();

    /** 异步 SSE 请求线程池 */
    private volatile ExecutorService executorService;
    /** 连接超时（毫秒） */
    private volatile int connectTimeoutMs;
    /** 读取超时（毫秒） */
    private volatile int readTimeoutMs;

    // ==================== 构造与初始化 ====================

    private HttpSSEClient() {
        init();
    }

    private void init() {
        SseClientConfig cfg = GlobalConfigManager.config().getSseClientSimpleConfig();
        if (cfg == null) {
            cfg = new SseClientConfig();
        }
        this.connectTimeoutMs = cfg.getConnectTimeoutMs();
        this.readTimeoutMs = cfg.getReadTimeoutMs();

        // 关闭旧线程池
        if (this.executorService != null && !this.executorService.isShutdown()) {
            this.executorService.shutdown();
        }

        // 创建新线程池
        AtomicInteger counter = new AtomicInteger(0);
        this.executorService = new ThreadPoolExecutor(4,
                128,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                r -> {
                    Thread t = new Thread(r, "hutool-sse-worker-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 获取单例实例
     */
    public static HttpSSEClient getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new HttpSSEClient();
                }
            }
        }
        return instance;
    }

    /**
     * 重置为默认配置（重新读取配置并重建线程池）
     */
    public void resetToDefault() {
        synchronized (LOCK) {
            init();
        }
    }

    // ==================== 同步请求方法 ====================

    /**
     * 同步GET请求
     *
     * @param url 请求URL
     * @return 响应字符串
     */
    public String getSync(String url) {
        return getSync(url, null);
    }

    /**
     * 同步GET请求（带请求头）
     *
     * @param url     请求URL
     * @param headers 请求头
     * @return 响应字符串
     */
    public String getSync(String url, Map<String, String> headers) {
        try {
            HttpRequest request = HttpRequest.get(url)
                    .setConnectionTimeout(connectTimeoutMs)
                    .setReadTimeout(readTimeoutMs);
            addHeaders(request, headers);
            try (HttpResponse response = request.execute()) {
                return handleResponse(response);
            }
        } catch (Exception e) {
            log.error("同步GET请求失败: {}", e.getMessage(), e);
            throw new RuntimeException("HTTP请求失败", e);
        }
    }

    /**
     * 同步GET请求（带超时）
     *
     * @param url     请求URL
     * @param timeout 超时时间（毫秒）
     * @return 响应字符串
     */
    public String getSync(String url, long timeout) {
        return getSync(url, null, timeout);
    }

    /**
     * 同步GET请求（带请求头和超时）
     */
    public String getSync(String url, Map<String, String> headers, long timeout) {
        try {
            HttpRequest request = HttpRequest.get(url)
                    .setConnectionTimeout((int) timeout)
                    .setReadTimeout((int) timeout);
            addHeaders(request, headers);
            try (HttpResponse response = request.execute()) {
                return handleResponse(response);
            }
        } catch (Exception e) {
            log.error("同步GET请求失败: {}", e.getMessage(), e);
            throw new RuntimeException("HTTP请求失败", e);
        }
    }

    /**
     * 同步POST请求（JSON格式）
     *
     * @param url      请求URL
     * @param jsonBody JSON请求体
     * @return 响应字符串
     */
    public String postJsonSync(String url, String jsonBody) {
        return postJsonSync(url, jsonBody, null);
    }

    /**
     * 同步POST请求（JSON格式，带请求头）
     */
    public String postJsonSync(String url, String jsonBody, Map<String, String> headers) {
        try {
            HttpRequest request = HttpRequest.post(url)
                    .setConnectionTimeout(connectTimeoutMs)
                    .setReadTimeout(readTimeoutMs)
                    .header("Content-Type", "application/json")
                    .body(jsonBody);
            addHeaders(request, headers);
            try (HttpResponse response = request.execute()) {
                return handleResponse(response);
            }
        } catch (Exception e) {
            log.error("同步POST请求失败: {}", e.getMessage(), e);
            throw new RuntimeException("同步POST请求失败:" + e.getMessage(), e);
        }
    }

    /**
     * 同步POST请求（表单格式）
     *
     * @param url        请求URL
     * @param formParams 表单参数
     * @return 响应字符串
     */
    public String postFormSync(String url, Map<String, String> formParams) {
        return postFormSync(url, formParams, null);
    }

    /**
     * 同步POST请求（表单格式，带请求头）
     */
    public String postFormSync(String url, Map<String, String> formParams, Map<String, String> headers) {
        try {
            HttpRequest request = HttpRequest.post(url)
                    .setConnectionTimeout(connectTimeoutMs)
                    .setReadTimeout(readTimeoutMs)
                    .header("Content-Type", "application/x-www-form-urlencoded");
            if (formParams != null && !formParams.isEmpty()) {
                formParams.forEach(request::form);
            }
            addHeaders(request, headers);
            try (HttpResponse response = request.execute()) {
                return handleResponse(response);
            }
        } catch (Exception e) {
            log.error("同步POST表单请求失败: {}", e.getMessage(), e);
            throw new RuntimeException("HTTP请求失败", e);
        }
    }

    // ==================== 同步请求（结构化响应：状态码 + 文本/二进制体） ====================

    /**
     * 同步POST请求（JSON格式，带请求头），返回结构化响应（携带 HTTP 状态码与原始响应体）
     * <p>非 2xx 不抛异常，由调用方依据 {@link HttpResp#getStatus()} 判定。</p>
     *
     * @param url      请求URL
     * @param jsonBody JSON请求体
     * @param headers  请求头
     * @return HttpResp（status + bodyUtf8 + contentType）
     */
    public HttpResp postJsonSyncDetailed(String url, String jsonBody, Map<String, String> headers) {
        try {
            HttpRequest request = HttpRequest.post(url)
                    .setConnectionTimeout(connectTimeoutMs)
                    .setReadTimeout(readTimeoutMs)
                    .header("Content-Type", "application/json")
                    .body(jsonBody);
            addHeaders(request, headers);
            try (HttpResponse response = request.execute()) {
                return handleResponseDetailed(response, false);
            }
        } catch (Exception e) {
            log.error("同步POST请求失败: {}", e.getMessage(), e);
            throw new RuntimeException("同步POST请求失败:" + e.getMessage(), e);
        }
    }

    /**
     * 同步POST请求（JSON格式，带请求头），返回二进制响应（audio/speech 等端点）
     *
     * @param url      请求URL
     * @param jsonBody JSON请求体
     * @param headers  请求头
     * @return HttpResp（status + bodyBytes + contentType）
     */
    public HttpResp postJsonSyncBytes(String url, String jsonBody, Map<String, String> headers) {
        try {
            HttpRequest request = HttpRequest.post(url)
                    .setConnectionTimeout(connectTimeoutMs)
                    .setReadTimeout(readTimeoutMs)
                    .header("Content-Type", "application/json")
                    .body(jsonBody);
            addHeaders(request, headers);
            try (HttpResponse response = request.execute()) {
                return handleResponseDetailed(response, true);
            }
        } catch (Exception e) {
            log.error("同步POST(二进制)请求失败: {}", e.getMessage(), e);
            throw new RuntimeException("同步POST(二进制)请求失败:" + e.getMessage(), e);
        }
    }

    /**
     * 同步POST multipart/form-data 请求（文件上传，内存零落盘）
     * <p>普通字段用 {@code FormPart.field(...)}；文件字段用 {@code FormPart.file(...)}（BytesResource）。</p>
     *
     * @param url     请求URL
     * @param parts   表单部件列表
     * @param headers 附加请求头
     * @return HttpResp（status + bodyUtf8）
     */
    public HttpResp postMultipartSync(String url, List<FormPart> parts, Map<String, String> headers) {
        try {
            HttpRequest request = HttpRequest.post(url)
                    .setConnectionTimeout(connectTimeoutMs)
                    .setReadTimeout(readTimeoutMs);
            if (parts != null) {
                for (FormPart part : parts) {
                    if (part == null || part.getName() == null) {
                        continue;
                    }
                    if (part.getFileBytes() != null) {
                        // 文件部分：内存资源上传，避免落盘
                        BytesResource resource = new BytesResource(part.getFileBytes(),
                                part.getFilename() == null ? "file" : part.getFilename());
                        request.form(part.getName(), resource);
                    } else {
                        request.form(part.getName(), part.getValue() == null ? "" : part.getValue());
                    }
                }
            }
            addHeaders(request, headers);
            try (HttpResponse response = request.execute()) {
                return handleResponseDetailed(response, false);
            }
        } catch (Exception e) {
            log.error("同步POST multipart 请求失败: {}", e.getMessage(), e);
            throw new RuntimeException("同步POST multipart 请求失败:" + e.getMessage(), e);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 添加请求头
     */
    private void addHeaders(HttpRequest request, Map<String, String> headers) {
        if (headers != null && !headers.isEmpty()) {
            headers.forEach(request::header);
        }
    }

    /**
     * 处理响应
     */
    private String handleResponse(HttpResponse response) {
        int status = response.getStatus();
        String body = response.body();
        if (status < 200 || status >= 300) {
            log.error("HTTP请求返回错误状态码: {}", status);
            log.error("错误响应内容: {}", body);
        }
        return body;
    }

    /**
     * 处理响应（结构化：状态码 + 文本/二进制体）
     *
     * @param response   Hutool 响应
     * @param asBytes    true 取二进制体（audio 等端点）；false 取 UTF-8 文本体
     * @return HttpResp
     */
    private HttpResp handleResponseDetailed(HttpResponse response, boolean asBytes) {
        int status = response.getStatus();
        String contentType = response.header("Content-Type");
        if (status < 200 || status >= 300) {
            log.error("HTTP请求返回错误状态码: {}", status);
        }
        if (asBytes) {
            return new HttpResp(status, response.bodyBytes(), contentType);
        }
        return new HttpResp(status, response.body(), contentType);
    }

    // ==================== SSE 请求方法 ====================

    /**
     * 发送SSE请求（同步方式）
     *
     * @param url          请求URL
     * @param eventHandler SSE事件处理器
     */
    public void getSseSync(String url, HttpStreamEventInterface eventHandler) {
        executeSseGet(url, null, eventHandler);
    }

    /**
     * 发送SSE请求（异步方式）
     *
     * @param url          请求URL
     * @param eventHandler SSE事件处理器
     * @return CompletableFuture<Void>
     */
    public CompletableFuture<Void> getSseAsync(String url, HttpStreamEventInterface eventHandler) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        HttpStreamEventInterface wrapped = wrapHandler(eventHandler, future);
        try {
            executorService.submit(() -> executeSseGet(url, null, wrapped));
        } catch (Exception e) {
            log.error("提交SSE请求任务失败: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 发送带自定义头的SSE请求
     *
     * @param url          请求URL
     * @param headers      自定义头信息
     * @param eventHandler SSE事件处理器
     * @return CompletableFuture<Void>
     */
    public CompletableFuture<Void> getSseWithHeaders(String url, Map<String, String> headers,
            HttpStreamEventInterface eventHandler) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        HttpStreamEventInterface wrapped = wrapHandler(eventHandler, future);
        try {
            executorService.submit(() -> executeSseGet(url, headers, wrapped));
        } catch (Exception e) {
            log.error("提交SSE请求任务失败: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 发送POST SSE请求
     *
     * @param url          请求URL
     * @param body         请求体
     * @param eventHandler SSE事件处理器
     * @return CompletableFuture<Void>
     */
    public CompletableFuture<Void> postSse(String url, String body, Map<String, String> headers,
            HttpStreamEventInterface eventHandler) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        HttpStreamEventInterface wrapped = wrapHandler(eventHandler, future);
        try {
            executorService.submit(() -> executeSsePost(url, body, headers, wrapped));
        } catch (Exception e) {
            log.error("提交POST SSE请求任务失败: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 简化的SSE事件监听器（流式处理数据）
     *
     * @param url          请求URL
     * @param dataConsumer 数据消费者
     */
    public void listenSseData(String url, Consumer<String> dataConsumer) {
        HttpStreamEventInterface handler = new HttpStreamEventInterface() {
            @Override
            public void onEvent(String eventType, String data, String id) {
                if ("message".equals(eventType) && data != null && !data.isEmpty()) {
                    dataConsumer.accept(data);
                }
            }

            @Override
            public void onComment(String comment) {
                // 忽略注释
            }

            @Override
            public void onComplete() {
                log.info("SSE流完成");
            }

            @Override
            public void onError(Throwable t) {
                log.error("SSE流错误: {}", t.getMessage());
            }
        };
        getSseAsync(url, handler);
    }

    // ==================== SSE 内部实现 ====================

    /**
     * 执行 SSE GET 请求
     */
    private void executeSseGet(String url, Map<String, String> headers, HttpStreamEventInterface eventHandler) {
        try {
            HttpRequest request = HttpRequest.get(url)
                    .setConnectionTimeout(connectTimeoutMs)
                    .setReadTimeout(readTimeoutMs)
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .header("Accept-Encoding", "identity");
            addHeaders(request, headers);
            executeSse(request, eventHandler);
        } catch (Exception e) {
            log.error("SSE请求失败: {}", e.getMessage(), e);
            eventHandler.onError(e);
        }
    }

    /**
     * 执行 SSE POST 请求
     */
    private void executeSsePost(String url, String body, Map<String, String> headers,
            HttpStreamEventInterface eventHandler) {
        try {
            HttpRequest request = HttpRequest.post(url)
                    .setConnectionTimeout(connectTimeoutMs)
                    .setReadTimeout(readTimeoutMs)
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .header("Accept-Encoding", "identity")
                    .header("Content-Type", "application/json")
                    .body(body);
            addHeaders(request, headers);
            executeSse(request, eventHandler);
        } catch (Exception e) {
            log.error("发起POST SSE请求失败: {}", e.getMessage(), e);
            eventHandler.onError(e);
        }
    }

    /**
     * 执行SSE请求并逐行解析事件流
     * <p>
     * 通过 Hutool HttpResponse.bodyStream() 获取底层 InputStream，
     * 使用 BufferedReader.readLine() 逐行读取并解析 SSE 协议字段。
     * </p>
     */
    private void executeSse(HttpRequest request, HttpStreamEventInterface eventHandler) {
        HttpResponse response = null;
        try {
            response = request.executeAsync();
            int statusCode = response.getStatus();
            String uri = request.getUrl();
            log.info("SSE请求响应状态 - URL: {}, 状态码: {}", uri, statusCode);

            if (statusCode < 200 || statusCode >= 300) {
                // 非 2xx：读取完整错误响应体并触发 onError（SseException 结构化携带状态码，message 保持原格式以兼容旧调用方）
                String body = response.body();
                log.error("SSE请求最终失败，URL: {}, 状态码: {}, 响应内容: {}", uri, statusCode, body);
                String errMsg = String.format("SSE请求失败，状态码: %s, 响应体: %s", statusCode, body);
                eventHandler.onError(new SseException(statusCode, String.valueOf(statusCode), errMsg, body));
                return;
            }

            // 获取响应体输入流
            InputStream is = response.bodyStream();
            if (is == null) {
                eventHandler.onComplete();
                return;
            }

            // 逐行读取 SSE 流
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String currentEventType = "message";
            String currentId = "";
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    // 空行：重置事件类型和ID（SSE 协议规定空行表示一个事件的结束）
                    currentEventType = "message";
                    currentId = "";
                } else if (line.startsWith(":")) {
                    // 注释行（以冒号开头）
                    eventHandler.onComment(line.substring(1).trim());
                } else if (line.startsWith("event:")) {
                    currentEventType = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    eventHandler.onEvent(currentEventType, data, currentId);
                } else if (line.startsWith("id:")) {
                    currentId = line.substring(3).trim();
                }
                // retry: 行可忽略
            }
            // 流正常结束
            eventHandler.onComplete();
        } catch (Exception e) {
            log.error("SSE流处理异常: {}", e.getMessage(), e);
            eventHandler.onError(e);
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Exception e) {
                    log.warn("关闭SSE响应失败: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 包装事件处理器，使 CompletableFuture 在 SSE 完成或出错时正确结束
     */
    private HttpStreamEventInterface wrapHandler(HttpStreamEventInterface eventHandler,
            CompletableFuture<Void> future) {
        return new HttpStreamEventInterface() {
            @Override
            public void onEvent(String eventType, String data, String id) {
                try {
                    eventHandler.onEvent(eventType, data, id);
                } catch (Exception e) {
                    log.error("处理SSE事件时出错: {}", e.getMessage(), e);
                }
            }

            @Override
            public void onComment(String comment) {
                try {
                    eventHandler.onComment(comment);
                } catch (Exception e) {
                    log.error("处理SSE注释时出错: {}", e.getMessage(), e);
                }
            }

            @Override
            public void onComplete() {
                try {
                    eventHandler.onComplete();
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }

            @Override
            public void onError(Throwable t) {
                try {
                    eventHandler.onError(t);
                } catch (Exception e) {
                    log.error("处理错误时出错: {}", e.getMessage(), e);
                }
                future.completeExceptionally(t);
            }
        };
    }

    // ==================== 客户端管理 ====================

    /**
     * 关闭客户端（释放线程池资源）
     */
    public void close() {
        try {
            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdown();
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            }
        } catch (Exception e) {
            log.error("关闭SSE客户端失败: {}", e.getMessage(), e);
        }
    }
}
