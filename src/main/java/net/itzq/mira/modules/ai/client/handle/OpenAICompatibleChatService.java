package net.itzq.mira.modules.ai.client.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.client.openai.chat.entity.ChatMessage;
import net.itzq.mira.modules.ai.client.config.IApiReqParamsCallback;
import net.itzq.mira.modules.ai.client.config.ModelApiConfig;
import net.itzq.mira.modules.ai.client.sse.HttpSSEClient;
import net.itzq.mira.modules.ai.client.sse.SseException;
import net.itzq.mira.modules.ai.tool.FCUtil;
import net.itzq.mira.modules.ai.client.openai.tool.Tool;
import net.itzq.mira.modules.config.GlobalConfigManager;
import net.itzq.mira.modules.config.SseClientConfig;
import net.itzq.mira.core.utils.StringUtils;

import java.lang.reflect.Constructor;
import java.util.*;

/**
 * OpenAICompatibleChatService
 *
 * @author tangzq
 */
@Slf4j
public class OpenAICompatibleChatService {

    private final HttpSSEClient httpSSEClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ModelApiConfig config;

    /** 工具注册表：请求里 functions → Tool 实体从本实例的注册表解析（多实例隔离） */
    private final net.itzq.mira.modules.ai.tool.ToolRegistry toolRegistry;

    /** SSE 超时快照（多实例）：取本 service 所属运行时的声明，请求时传给 HttpSSEClient */
    private final SseClientConfig sseTimeouts;

    public OpenAICompatibleChatService(ModelApiConfig config) {
        this(config, net.itzq.mira.modules.runtime.KernelRuntime.defaultRuntime().toolRegistry());
    }

    public OpenAICompatibleChatService(ModelApiConfig config,
                                       net.itzq.mira.modules.ai.tool.ToolRegistry toolRegistry) {
        this.config = config;
        this.toolRegistry = toolRegistry == null
                ? net.itzq.mira.modules.runtime.KernelRuntime.defaultRuntime().toolRegistry()
                : toolRegistry;
        this.httpSSEClient = HttpSSEClient.getInstance();
        // 多实例：SSE 超时取**本 service 所属运行时**的声明（经注册表反向引用），
        // 声明 reset/import 时 service 随之重建、快照随之更新
        this.sseTimeouts = resolveSseTimeouts(this.toolRegistry);
    }

    /**
     * 解析本 service 生效的 SSE 超时配置：
     * 所属运行时的声明优先；注册表未绑定运行时 / 声明未配置时回落默认运行时声明。
     */
    static SseClientConfig resolveSseTimeouts(net.itzq.mira.modules.ai.tool.ToolRegistry registry) {
        SseClientConfig cfg = null;
        if (registry != null && registry.getRuntime() != null) {
            cfg = registry.getRuntime().declaration().getSseClientSimpleConfig();
        }
        if (cfg == null) {
            cfg = GlobalConfigManager.config().getSseClientSimpleConfig();
        }
        return cfg != null ? cfg : new SseClientConfig();
    }

    /** 本 service 生效的 SSE 超时快照（测试与诊断用） */
    public SseClientConfig getSseTimeouts() {
        return sseTimeouts;
    }

    /** 模型注册配置（含视觉能力声明等） */
    public ModelApiConfig getConfig() {
        return config;
    }

    public String sendChat(String question) throws Exception {
        List<ChatMessage> chatMessages = Arrays.asList(ChatMessage.withUser(question));
        return sendChat(chatMessages);
    }

    public String sendChat(List<ChatMessage> chatMessages) throws Exception {

        ApiRequestParams apiRequestParams = new ApiRequestParams();
        apiRequestParams.setMessages(chatMessages);

        return getResult(apiRequestParams, null, null);
    }

    public String sendChatStream(String question, SseEventListener listener) throws Exception {
        List<ChatMessage> chatMessages = Arrays.asList(ChatMessage.withUser(question));

        return sendChatStream(chatMessages, listener);
    }

    public String sendChatStream(List<ChatMessage> chatMessages, SseEventListener listener) throws Exception {

        ApiRequestParams apiRequestParams = new ApiRequestParams();
        apiRequestParams.setMessages(chatMessages);

        return getResult(apiRequestParams, null, listener);
    }

    public String getResult(ApiRequestParams apiRequestParams, IApiReqParamsCallback apiReqParamsCallback,
            SseEventListener listener) throws Exception {
        String baseUrl = config.getApiHost();
        String apiKey = config.getApiKey();
        String chatCompletionUrl = config.getApiEndpoint();

        // 组装请求参数
        if (apiRequestParams == null) {
            apiRequestParams = new ApiRequestParams();
        }

        apiRequestParams.setModel(config.getApiModelName());

        List<ChatMessage> messages = apiRequestParams.getMessages();
        if (messages == null) {
            messages = new ArrayList<>();
        }
        apiRequestParams.setMessages(messages);

        if (apiRequestParams.getFunctions() != null && !apiRequestParams.getFunctions().isEmpty()) {
            List<Tool> tools = toolRegistry.getAllFunctionTools(apiRequestParams.getFunctions());
            apiRequestParams.setTools(tools);
        }

        if (listener != null) {
            apiRequestParams.setStream(true);
        } else {
            apiRequestParams.setStream(false);
        }

        if (apiReqParamsCallback != null) {
            apiReqParamsCallback.callback(apiRequestParams);
        }

        Map<String, Object> chatCompletion = apiRequestParams.getAllParams();
        // 构造请求
        ObjectMapper mapper = new ObjectMapper();
        String requestString = mapper.writeValueAsString(chatCompletion);

        Map<String, String> apiHeaders = config.getApiHeaders();
        if (apiHeaders == null) {
            apiHeaders = new LinkedHashMap<>();
            if (StringUtils.isNotBlank(apiKey)){
                apiHeaders.put("Authorization", "Bearer " + apiKey);
            }
        }

        String api = baseUrl + chatCompletionUrl;

        if (listener != null) {

            HttpStreamEventInterface handler;
            try {
                // 创建流式事件处理器
                String sseEventHandlerClassName = config.getSseEventHandler();

                Class<? extends HttpStreamEventInterface> sseEventHandler =  StreamEventHandlerManage.resolve(sseEventHandlerClassName,false);;
                if (sseEventHandler == null) {
                    log.warn("加载 SSE 事件处理器类失败（未注册别名）: {}, 将使用默认处理器", sseEventHandlerClassName);
                    sseEventHandler = OpenAICompatibleStreamEventHandler.class;
                }

                Constructor<? extends HttpStreamEventInterface> constructor =
                        sseEventHandler.getConstructor(SseEventListener.class);

                HttpStreamEventInterface handlerInstance = constructor.newInstance(listener);

                handler = handlerInstance;

            } catch (Exception e) {
                // 实例化失败（如抽象类、权限问题、构造方法内部异常）
                log.error("创建事件处理器失败");
                throw new SseException("创建事件处理器失败", e);
            }

            httpSSEClient.postSse(api, requestString, apiHeaders, handler, sseTimeouts);
            return null;
        } else {
            String response = httpSSEClient.postJsonSync(api, requestString, apiHeaders, sseTimeouts);
            log.info("[AI Response] {}", StringUtils.left(response, 300));
            return response;
        }

    }


}
