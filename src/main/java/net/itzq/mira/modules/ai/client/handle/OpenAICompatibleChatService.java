package net.itzq.mira.modules.ai.client.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.client.openai.chat.entity.ChatMessage;
import net.itzq.mira.modules.ai.client.config.IApiReqParamsCallback;
import net.itzq.mira.modules.ai.client.config.ModelApiConfig;
import net.itzq.mira.modules.ai.client.sse.HttpSSEClient;
import net.itzq.mira.modules.ai.client.sse.SseException;
import net.itzq.mira.modules.ai.client.tool.FCUtil;
import net.itzq.mira.modules.ai.client.openai.tool.Tool;
import org.apache.commons.lang3.StringUtils;

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

    public OpenAICompatibleChatService(ModelApiConfig config) {
        // 使用HttpSSEClient默认配置，可根据需要调整
        this.config = config;
        this.httpSSEClient = HttpSSEClient.getInstance();
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
            List<Tool> tools = FCUtil.getAllFunctionTools(apiRequestParams.getFunctions());
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

            httpSSEClient.postSse(api, requestString, apiHeaders, handler);
            return null;
        } else {
            String response = httpSSEClient.postJsonSync(api, requestString, apiHeaders);
            log.info("[AI Response] {}", StringUtils.left(response, 300));
            return response;
        }

    }


}
