package net.itzq.mira.modules.ai;

import cn.hutool.http.HttpUtil;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.agent.BasicAgent;
import net.itzq.mira.modules.ai.agent.BasicClient;
import net.itzq.mira.modules.ai.agent.event.EventCenter;
import net.itzq.mira.modules.ai.agent.event.EventHook;
import net.itzq.mira.modules.ai.agent.event.EventListener;
import net.itzq.mira.modules.ai.agent.event.type.*;
import net.itzq.mira.modules.ai.client.config.ApiProviderManage;
import net.itzq.mira.modules.ai.client.config.ModelApiConfig;
import net.itzq.mira.modules.ai.client.handle.ApiRequestParams;
import net.itzq.mira.modules.ai.client.handle.OpenAICompatibleStreamEventHandler;
import net.itzq.mira.modules.ai.client.openai.chat.entity.ChatMessage;
import net.itzq.mira.modules.ai.client.openai.tool.ToolCall;
import net.itzq.mira.modules.ai.client.tool.FCUtil;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
import net.itzq.mira.modules.ai.persistence.AbstractHistoryPersist;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * QAI Runtime Min 使用示例
 *
 * 包含所有高级用法的详细示例
 */
public class Main {

    // ==================== 模型配置 ====================

    /**
     * 示例1：注册AI模型
     */
    private static void setupModels() {


        // 配置本地模型（如LM Studio、vLLM等）
        ModelApiConfig localConfig = ModelApiConfig.builder()
                .alias("local-model")
                .providerName("Local")
                .apiModelName("qwen/qwen3-vl-8b")
                .apiHost("http://127.0.0.1:1234")
                .apiEndpoint("/v1/chat/completions")
                .apiKey("")
                .sseEventHandler(OpenAICompatibleStreamEventHandler.class.getName())
                .build();

        // 注册模型
        ApiProviderManage.getInstance().registerModel(localConfig);

        // 设置默认模型
        ApiProviderManage.getInstance().setDefaultModel("local-model");

        // 自定义HTTP客户端配置（可选）
        // HttpSSEClient.getInstance().setAsyncHttpClient(customClient);
    }

    // ==================== 基本对话 ====================

    /**
     * 示例2：基本同步对话
     */
    private static void basicChat() {
        AgentContextHolder context = AgentContextHolder.builder().prompt("你是一个有用的AI助手")

                .build();

        BasicAgent agent = new BasicAgent(context, "basic-agent");

        String response = agent.chat("你好");
        System.out.println("基本对话回复: " + response);
    }

    // ==================== 工具调用 ====================

    /**
     * 示例3：定义自定义工具
     */
    public static class MyTools {

        //@Tool(name = "get_weather", description = "获取指定城市的天气信息")
        public String getWeather(@ToolParam(description = "城市名称") String city, AgentContextHolder context) {
            // 实际项目中这里会调用天气API
            return city + "今天天气晴朗，温度25°C，湿度60%";
        }

        //@Tool(name = "calculate", description = "执行数学计算")
        public String calculate(@ToolParam(description = "数学表达式，如 2+3*4") String expression,
                AgentContextHolder context) {
            try {
                // 简单的表达式计算示例
                double result = evaluateExpression(expression);
                return "计算结果: " + expression + " = " + result;
            } catch (Exception e) {
                return "计算失败: " + e.getMessage();
            }
        }

        //@Tool(name = "search_files", description = "搜索指定目录下的文件")
        public String searchFiles(@ToolParam(description = "搜索关键词") String keyword,
                @ToolParam(description = "目录路径") String directory, AgentContextHolder context) {
            // 实际项目中这里会执行文件搜索
            return "在 " + directory + " 中找到包含 '" + keyword + "' 的文件: file1.txt, file2.java";
        }

        private double evaluateExpression(String expression) {
            // 简单实现，实际项目可使用ScriptEngine
            return 0.0;
        }
    }

    /**
     * 示例4：使用工具进行对话
     */
    private static void chatWithTools() {
        AgentContextHolder context = AgentContextHolder.builder().prompt("你是一个智能助手，可以查询天气、执行计算和搜索文件")

                .tools(Arrays.asList("get_weather", "calculate", "search_files")).build();

        BasicAgent agent = new BasicAgent(context, "tool-agent");

        // AI会自动识别需要调用的工具
        String response = agent.chat("北京今天天气怎么样？");
        System.out.println("工具调用回复: " + response);

        // 复杂查询，可能需要多次工具调用
        String complexResponse = agent.chat("帮我计算 (25+75)*2 的结果，并搜索当前目录下的Java文件");
        System.out.println("复杂查询回复: " + complexResponse);
    }

    // ==================== 流式对话 ====================

    /**
     * 示例5：流式对话（带完整事件监听）
     */
    private static void streamChat() throws InterruptedException {
        // 创建事件总线
        EventCenter eventCenter = new EventCenter();

        // 注册事件监听器
        eventCenter.register(new EventListener() {
            @Override
            public void onEvent(GeneralEvent event) {
                // 流式输出的每个chunk
                System.out.print(event.getData());
            }

            @Override
            public void onChatInput(ChatInputEvent event) {
                System.out.println("\n[用户输入] " + event.getQuestion());
            }

            @Override
            public void onStepBegin(StepBeginEvent event) {
                System.out.println("\n[步骤开始] 深度: " + event.getDeep());
            }

            @Override
            public void onStepEnd(StepEndEvent event) {
                System.out.println("\n[步骤结束]");
            }

            @Override
            public void onCallToolBegin(CallToolBeginEvent event) {
                String toolName = event.getToolCall().getFunction().getName();
                System.out.println("\n[开始调用工具] " + toolName);
            }

            @Override
            public void onCallToolEnd(CallToolEndEvent event) {
                System.out.println("\n[工具调用完成]");
            }

            @Override
            public void onChatEnd(ChatEndEvent event) {
                System.out.println("\n[对话结束] 成功: " + event.isSuccess());
            }

            @Override
            public void onError(ErrorEvent event) {
                System.out.println("\n[错误] " + event.getThrowable().getMessage());
            }
        });

        AgentContextHolder context = AgentContextHolder.builder().prompt("你是一个有用的AI助手，请详细回答问题")
                .eventCenter(eventCenter).build();

        BasicAgent agent = new BasicAgent(context, "stream-agent");

        // 流式对话
        System.out.println("=== 流式对话开始 ===");
        CountDownLatch latch = agent.chatStream("请详细解释量子计算的基本原理");
        latch.await(); // 等待流式对话完成
        System.out.println("\n=== 流式对话结束 ===");
    }

    // ==================== 事件钩子 ====================

    /**
     * 示例6：使用事件钩子（EventHook）
     */
    private static void chatWithEventHook() {
        // 创建事件钩子
        EventHook eventHook = new EventHook() {
            @Override
            public void onEvent(GeneralEvent event) {
                // 可以在这里添加日志、监控等
                System.out.println("[Hook] 收到事件: " + event.getEventType());
            }

            @Override
            public void onChatInput(ChatInputEvent event) {
                System.out.println("[Hook] 用户输入: " + event.getQuestion());
            }

            @Override
            public void onCallToolBegin(CallToolBeginEvent event) {
                String toolName = event.getToolCall().getFunction().getName();
                String args = event.getToolCall().getFunction().getArguments();
                System.out.println("[Hook] 工具调用开始: " + toolName + ", 参数: " + args);
            }

            @Override
            public void onCallToolEnd(CallToolEndEvent event) {
                System.out.println("[Hook] 工具调用结束: " + event.getEndMsg());
            }

            @Override
            public void onChatEnd(ChatEndEvent event) {
                System.out.println("[Hook] 对话结束，成功: " + event.isSuccess());
            }

            @Override
            public void onError(ErrorEvent event) {
                System.out.println("[Hook] 发生错误: " + event.getThrowable().getMessage());
            }
        };

        AgentContextHolder context = AgentContextHolder.builder().prompt("你是一个有用的AI助手")

                .tools(Arrays.asList("get_weather")).eventHook(eventHook).build();

        BasicAgent agent = new BasicAgent(context, "hook-agent");
        String response = agent.chat("上海今天天气怎么样？");
        System.out.println("回复: " + response);
    }

    // ==================== 历史记录持久化 ====================

    /**
     * 示例7：历史记录持久化
     */
    private static void chatWithHistoryPersist() {
        // 自定义历史记录持久化实现
        AbstractHistoryPersist historyPersist = new AbstractHistoryPersist() {
            @Override
            public void saveChatMessages(List<ChatMessage> chatMessages, AgentContextHolder context) {
                System.out.println("=== 保存聊天记录 ===");
                for (ChatMessage message : chatMessages) {
                    System.out.println("  [" + message.getRole() + "] " + (message.getContent() != null ?
                            message.getContent().getText() :
                            "[工具调用]"));
                }
                // 实际项目中保存到数据库
                // chatMessageRepository.saveAll(chatMessages);
            }

            @Override
            public void saveEventMessages(List<?> eventMessages) {
                System.out.println("=== 保存事件消息: " + eventMessages.size() + " 条 ===");
                // 实际项目中保存到数据库
            }
        };

        AgentContextHolder context = AgentContextHolder.builder().prompt("你是一个有用的AI助手")

                .build();

        BasicAgent agent = new BasicAgent(context, "persist-agent");
        agent.setHistoryPersist(historyPersist);

        String response = agent.chat("你好");
        System.out.println("回复: " + response);
    }

    // ==================== BasicClient 单次调用 ====================

    /**
     * 示例8：BasicClient 单次调用模式
     */
    private static void basicClientDemo() {
        // 初始化 Context 和 Client
        AgentContextHolder contextHolder = AgentContextHolder.builder().prompt("你是一个有用的AI助手")

                .tools(Arrays.asList("get_weather", "calculate")).build();

        BasicClient client = new BasicClient(contextHolder, "my-client");

        // 发起第一次单次请求
        String userQuestion = "北京今天天气怎么样？";
        BasicClient.ClientResponse response = client.chat(userQuestion);

        // 判断 AI 的响应：是直接回复了，还是要求调用工具？
        if (response.hasToolCalls()) {
            // AI 要求调用工具，手动处理
            System.out.println("AI 请求调用工具:");
            for (ToolCall toolCall : response.getToolCalls()) {
                String funcName = toolCall.getFunction().getName();
                String args = toolCall.getFunction().getArguments();
                System.out.println("  - 工具: " + funcName + ", 参数: " + args);

                // 手动执行工具
                String toolResult = FCUtil.invoke(funcName, args, contextHolder);
                System.out.println("  工具执行结果: " + toolResult);

                // 必须手动将工具执行结果添加到历史中
                contextHolder.addHistory(ChatMessage.withTool(toolResult, toolCall.getId()));

                // 或者拒绝执行工具
                // String denyMsg = "用户拒绝执行 " + funcName + " 工具";
                // contextHolder.addHistory(ChatMessage.withTool(denyMsg, toolCall.getId()));
            }

            // 将工具执行结果再次发给 AI 总结
            System.out.println("将工具结果发回给 AI 进行总结...");
            BasicClient.ClientResponse secondResponse = client.chat(null);
            System.out.println("AI 最终回复: " + secondResponse.getContent());

        } else {
            // AI 直接给出了文本回复
            System.out.println("AI 直接回复: " + response.getContent());
        }
    }

    // ==================== API请求参数自定义 ====================

    /**
     * 示例9：自定义API请求参数
     */
    private static void customApiParams() {
        // 创建自定义请求参数
        ApiRequestParams customParams = new ApiRequestParams();
        customParams.setTemperature(0.7f);
        customParams.setMaxTokens(2000);
        customParams.setTopP(0.9f);
        customParams.setFrequencyPenalty(0.5f);
        customParams.setPresencePenalty(0.5f);

        // 添加自定义参数（适配不同厂商的特定参数）
        customParams.addCustomParam("custom_param", "value");

        AgentContextHolder context = AgentContextHolder.builder().prompt("你是一个创意写作助手")

                .requestParams(customParams).build();

        BasicAgent agent = new BasicAgent(context, "creative-agent");
        String response = agent.chat("写一首关于春天的诗");
        System.out.println("创意回复: " + response);
    }

    // ==================== 多模态消息 ====================

    /**
     * 示例10：多模态消息（文本+图片）
     * 需要使用支持视觉的模型，如 gpt-4o、qwen-vl 等
     */
    private static void multimodalChat() {
        AgentContextHolder context = AgentContextHolder.builder()
                .prompt("你是一个图像分析助手，请详细描述图片内容")
                .build();

        BasicClient client = new BasicClient(context, "vision-client");

        // 方式1：通过图片URL发送多模态消息
        //        ChatMessage urlMessage = ChatMessage.withUser(
        //                "请描述这张图片的内容",
        //                "https://example.com/image.jpg"
        //        );
        //        context.addHistory(urlMessage);
        //        BasicClient.ClientResponse response1 = client.chat(null);
        //        System.out.println("URL图片分析: " + response1.getContent());

        // 方式2：本地生成图片转Base64发送
        String base64Image = generateBase64Image("你好世界\nHello World");
        ChatMessage base64Message = ChatMessage.withUser("这张图片里有什么？", base64Image);
        context.addHistory(base64Message);
        BasicClient.ClientResponse response2 = client.chat(null);
        System.out.println("Base64图片分析: " + response2.getContent());

        // 方式3：多张图片对比分析
        //        ChatMessage multiImageMessage = ChatMessage.withUser(
        //                "请对比这两张图片的异同",
        //                "https://example.com/image1.jpg",
        //                "https://example.com/image2.jpg"
        //        );
        //        context.addHistory(multiImageMessage);
        //        BasicClient.ClientResponse response3 = client.chat(null);
        //        System.out.println("多图对比分析: " + response3.getContent());
    }

    /**
     * 生成一张 64x64 的图片，写入文字后转 Base64
     */
    private static String generateBase64Image(String text) {
        try {
            int width = 64;
            int height = 64;
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width,
                    height,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);

            java.awt.Graphics2D g2d = image.createGraphics();

            // 背景
            g2d.setColor(java.awt.Color.WHITE);
            g2d.fillRect(0, 0, width, height);

            // 文字
            g2d.setColor(java.awt.Color.BLACK);
            g2d.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 10));

            // 按换行符拆分文字逐行绘制
            String[] lines = text.split("\\\\n|\n");
            int y = 15;
            for (String line : lines) {
                g2d.drawString(line, 4, y);
                y += 14;
            }
            g2d.dispose();

            // 转 Base64
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", baos);
            String base64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
            return "data:image/png;base64," + base64;
        } catch (Exception e) {
            throw new RuntimeException("生成图片失败", e);
        }
    }

    // ==================== 主方法 ====================

    public static void main(String[] args) throws InterruptedException {

        HttpUtil.createServer(8888).start();

        // 1. 设置模型
        setupModels();

        // 2. 基本对话
        basicChat();

        // 4. 工具调用
        chatWithTools();

        // 5. 流式对话
        streamChat();

        // 6. 事件钩子
        chatWithEventHook();

        // 7. 历史记录持久化
        chatWithHistoryPersist();

        // 8. BasicClient 单次调用
        basicClientDemo();

        // 9. 自定义API参数
        customApiParams();

        // 10. 多模态
        multimodalChat();


    }
}
