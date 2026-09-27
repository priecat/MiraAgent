package net.itzq.mira.modules.example;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import net.itzq.mira.core.utils.IdGen;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.agent.AutoAgent;
import net.itzq.mira.modules.ai.agent.BasicAgent;
import net.itzq.mira.modules.ai.agent.BasicClient;
import net.itzq.mira.modules.ai.agent.event.EventCenter;
import net.itzq.mira.modules.ai.agent.event.EventHook;
import net.itzq.mira.modules.ai.agent.event.EventListener;
import net.itzq.mira.modules.ai.agent.event.type.CallToolBeginEvent;
import net.itzq.mira.modules.ai.agent.event.type.CallToolEndEvent;
import net.itzq.mira.modules.ai.agent.event.type.ChatEndEvent;
import net.itzq.mira.modules.ai.agent.event.type.ChatInputEvent;
import net.itzq.mira.modules.ai.agent.event.type.ErrorEvent;
import net.itzq.mira.modules.ai.agent.event.type.GeneralEvent;
import net.itzq.mira.modules.ai.agent.event.type.StepBeginEvent;
import net.itzq.mira.modules.ai.agent.event.type.StepEndEvent;
import net.itzq.mira.modules.ai.client.config.ModelApiConfig;
import net.itzq.mira.modules.ai.client.handle.ApiRequestParams;
import net.itzq.mira.modules.ai.client.openai.chat.entity.ChatMessage;
import net.itzq.mira.modules.ai.client.openai.tool.ToolCall;
import net.itzq.mira.modules.ai.entity.chat.AnsResponse;
import net.itzq.mira.modules.ai.persistence.PersistencePort;
import net.itzq.mira.modules.ai.persistence.PortHistoryPersist;
import net.itzq.mira.modules.ai.tool.ToolCallResult;
import net.itzq.mira.modules.ai.tool.annotation.Tool;
import net.itzq.mira.modules.ai.tool.annotation.ToolParam;
import net.itzq.mira.modules.runtime.KernelRuntime;

/**
 * 核心用法示例（原 {@code net.itzq.mira.modules.ai.Main}，已迁移并重写为多实例写法）。
 *
 * <h3>唯一的接线点：{@link KernelRuntime}</h3>
 * <p>每个示例都先 {@code KernelRuntime.builder().name(...).dataDir(...).build().start()} 起一个
 * <b>独立运行时</b>，再经它取声明、模型注册表、工具注册表、持久化端口——**不再使用任何静态 facade**
 * （{@code ApiProviderManage} / {@code FCUtil} / {@code GlobalConfigManager.config()} 均已废弃）。
 *
 * <p>同一 JVM 里起多个运行时（不同 {@code name} + {@code dataDir}）彼此隔离：模型、工具、VFS、技能各自独立。
 *
 * <p>运行本示例需要一个可用的模型服务；示例默认指向本地 LM Studio（{@code 127.0.0.1:1234}）。
 * 没有可用模型时，各段会打印提示后跳过，不影响阅读装配代码。
 */
public class CoreExample {

    /** 示例模型别名（声明与 holder 保持一致） */
    private static final String MODEL_ALIAS = "local-model";

    // ==================== 运行时装配（替代旧的 ApiProviderManage.getInstance()） ====================

    /**
     * 装配一个独立的内核运行时：声明里放模型，工具注册表里放示例工具。
     *
     * <p>旧写法是 {@code ApiProviderManage.getInstance().registerModel(...)} 全局注册；
     * 新写法把"世界有什么"写进**本实例**的声明并应用，多实例互不可见。
     */
    private static KernelRuntime buildRuntime() throws Exception {
        String dataDir = Files.createTempDirectory("mira-core-example").toString();

        KernelRuntime rt = KernelRuntime.builder()
                .name("core-example")
                .dataDir(dataDir)
                .build()
                .start();

        // 声明：模型 + 默认模型（应用到本实例的注册表）
        ModelApiConfig localModel = ModelApiConfig.builder()
                .alias(MODEL_ALIAS)
                .providerName("local")
                .apiModelName("qwen/qwen3-vl-8b")
                .apiHost("http://127.0.0.1:1234")
                .apiEndpoint("/v1/chat/completions")
                .apiKey("")
                .build();
        rt.declaration().setModels(Arrays.asList(localModel));
        rt.declaration().getAgentConfig().setDefaultModel(MODEL_ALIAS);
        rt.declaration().applyAllInstance();

        // 宿主工具注册（source 标注，便于声明导出分组）
        rt.toolRegistry().scanTools("app:core-example", MyTools.class);
        return rt;
    }

    /** 便捷：建一个绑定到指定运行时的会话上下文 */
    private static AgentContextHolder newHolder(KernelRuntime rt, String prompt, List<String> tools) {
        AgentContextHolder holder = AgentContextHolder.builder()
                .runtime(rt)
                .userId("u-example")
                .historyId(IdGen.uuid())
                .modelAlias(MODEL_ALIAS)
                .prompt(prompt)
                .build();
        if (tools != null && !tools.isEmpty()) {
            holder.setTools(tools);
        }
        return holder;
    }

    // ==================== 示例1：基本同步对话 ====================

    private static void basicChat(KernelRuntime rt) {
        AgentContextHolder holder = newHolder(rt, "你是一个有用的AI助手", null);
        BasicAgent agent = new BasicAgent(holder, "basic-agent");

        String response = agent.chat("你好");
        System.out.println("基本对话回复: " + response);
    }

    // ==================== 示例2：自定义工具 ====================

    /** 自定义工具：方法带 {@code @Tool} 注解，参数带 {@code @ToolParam} */
    public static class MyTools {

        @Tool(name = "get_weather", display = "天气", description = "获取指定城市的天气信息")
        public String getWeather(@ToolParam(description = "城市名称") String city,
                                 AgentContextHolder context) {
            // 实际项目中这里调用天气 API；工具结果建议用 ToolCallResult 包一层
            return ToolCallResult.success(city + "今天天气晴朗，温度25°C，湿度60%");
        }

        @Tool(name = "calculate", display = "计算", description = "执行数学计算，如 2+3*4")
        public String calculate(@ToolParam(description = "数学表达式") String expression) {
            try {
                return ToolCallResult.success("计算结果: " + expression + " = " + evaluate(expression));
            } catch (Exception e) {
                return ToolCallResult.error("计算失败: " + e.getMessage());
            }
        }

        @Tool(name = "search_files", display = "搜索文件", description = "按关键词搜索指定目录下的文件")
        public String searchFiles(@ToolParam(description = "搜索关键词") String keyword,
                                  @ToolParam(description = "目录路径") String directory) {
            return ToolCallResult.success("在 " + directory + " 中找到包含 '" + keyword
                    + "' 的文件: file1.txt, file2.java");
        }

        /** 极简表达式求值（示例用，生产请换 ScriptEngine 或表达式库） */
        private static double evaluate(String expression) {
            return 0.0d;
        }
    }

    // ==================== 示例3：使用工具进行对话 ====================

    private static void chatWithTools(KernelRuntime rt) {
        AgentContextHolder holder = newHolder(rt,
                "你是一个智能助手，可以查询天气、执行计算和搜索文件",
                Arrays.asList("get_weather", "calculate", "search_files"));
        BasicAgent agent = new BasicAgent(holder, "tool-agent");

        // AI 会自动识别需要调用的工具
        System.out.println("工具调用回复: " + agent.chat("北京今天天气怎么样？"));
        System.out.println("复杂查询回复: " + agent.chat("帮我计算 (25+75)*2 的结果，并搜索当前目录下的Java文件"));
    }

    // ==================== 示例4：流式对话（完整事件监听） ====================

    private static void streamChat(KernelRuntime rt) throws InterruptedException {
        EventCenter eventCenter = new EventCenter();
        eventCenter.register(new EventListener() {
            @Override
            public void onEvent(GeneralEvent event) {
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
                System.out.println("\n[开始调用工具] " + event.getToolCall().getFunction().getName());
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

        AgentContextHolder holder = newHolder(rt, "你是一个有用的AI助手，请详细回答问题", null);
        holder.setEventCenter(eventCenter);
        BasicAgent agent = new BasicAgent(holder, "stream-agent");

        System.out.println("=== 流式对话开始 ===");
        CountDownLatch latch = agent.chatStream("请详细解释量子计算的基本原理");
        latch.await();
        System.out.println("\n=== 流式对话结束 ===");
    }

    // ==================== 示例5：事件钩子（EventHook） ====================

    private static void chatWithEventHook(KernelRuntime rt) {
        EventHook eventHook = new EventHook() {
            @Override
            public void onEvent(GeneralEvent event) {
                System.out.println("[Hook] 收到事件: " + event.getEventType());
            }

            @Override
            public void onChatInput(ChatInputEvent event) {
                System.out.println("[Hook] 用户输入: " + event.getQuestion());
            }

            @Override
            public void onCallToolBegin(CallToolBeginEvent event) {
                ToolCall call = event.getToolCall();
                System.out.println("[Hook] 工具调用开始: " + call.getFunction().getName()
                        + ", 参数: " + call.getFunction().getArguments());
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

        AgentContextHolder holder = newHolder(rt, "你是一个有用的AI助手", Arrays.asList("get_weather"));
        holder.setEventHook(eventHook);
        BasicAgent agent = new BasicAgent(holder, "hook-agent");

        System.out.println("回复: " + agent.chat("上海今天天气怎么样？"));
    }

    // ==================== 示例6：历史持久化（PersistencePort） ====================

    private static void chatWithHistoryPersist(KernelRuntime rt) {
        // 端口：宿主实现（这里用内存版；生产可落库）
        PersistencePort port = new PersistencePort() {
            @Override
            public List<ChatMessage> loadContext(String historyId) {
                return new java.util.ArrayList<>();
            }

            @Override
            public void persistContext(String historyId, List<ChatMessage> messages) {
                System.out.println("=== 保存聊天记录: " + messages.size() + " 条 ===");
                for (ChatMessage m : messages) {
                    System.out.println("  [" + m.getRole() + "] "
                            + (m.getContent() != null ? m.getContent().getText() : "[工具调用]"));
                }
            }

            @Override
            public void appendEvent(String historyId, AnsResponse event) {
                // 逐条落库，供回放
            }

            @Override
            public void ensureSession(String historyId, String title, String mode) {
                // 会话行不存在则建
            }
        };

        AgentContextHolder holder = newHolder(rt, "你是一个有用的AI助手", null);
        BasicAgent agent = new BasicAgent(holder, "persist-agent");
        // 内核在每轮结束后经 historyPersist 落库
        agent.setHistoryPersist(PortHistoryPersist.of(port, holder.getHistoryId()));

        System.out.println("回复: " + agent.chat("你好"));
    }

    // ==================== 示例7：BasicClient 单次调用（手动工具回填） ====================

    private static void basicClientDemo(KernelRuntime rt) {
        AgentContextHolder holder = newHolder(rt, "你是一个有用的AI助手",
                Arrays.asList("get_weather", "calculate"));
        BasicClient client = new BasicClient(holder, "my-client");

        BasicClient.ClientResponse response = client.chat("北京今天天气怎么样？");

        if (response.hasToolCalls()) {
            System.out.println("AI 请求调用工具:");
            for (ToolCall toolCall : response.getToolCalls()) {
                String funcName = toolCall.getFunction().getName();
                String args = toolCall.getFunction().getArguments();
                System.out.println("  - 工具: " + funcName + ", 参数: " + args);

                // 手动执行工具：走本实例的工具注册表（替代旧的 FCUtil.invoke）
                String toolResult = rt.toolRegistry().invoke(funcName, args, holder);
                System.out.println("  工具执行结果: " + toolResult);

                // 必须手动把结果回填进历史
                holder.addHistory(ChatMessage.withTool(toolResult, toolCall.getId()));
            }

            BasicClient.ClientResponse second = client.chat(null);
            System.out.println("AI 最终回复: " + second.getContent());
        } else {
            System.out.println("AI 直接回复: " + response.getContent());
        }
    }

    // ==================== 示例8：自定义请求参数 ====================

    private static void customApiParams(KernelRuntime rt) {
        ApiRequestParams customParams = new ApiRequestParams();
        customParams.setTemperature(0.7f);
        customParams.setMaxTokens(2000);
        customParams.setTopP(0.9f);
        customParams.setFrequencyPenalty(0.5f);
        customParams.setPresencePenalty(0.5f);
        // 适配厂商私有参数：逐键透传
        customParams.addCustomParam("custom_param", "value");

        AgentContextHolder holder = newHolder(rt, "你是一个创意写作助手", null);
        holder.setRequestParams(customParams);
        BasicAgent agent = new BasicAgent(holder, "creative-agent");

        System.out.println("创意回复: " + agent.chat("写一首关于春天的诗"));
    }

    // ==================== 示例9：多模态消息（文本 + 图片） ====================

    private static void multimodalChat(KernelRuntime rt) {
        AgentContextHolder holder = newHolder(rt, "你是一个图像分析助手，请详细描述图片内容", null);
        BasicClient client = new BasicClient(holder, "vision-client");

        // 图片可以是 URL，也可以是 data URI（base64）。这里用占位串演示装配方式。
        String imageUrlOrDataUri = "data:image/png;base64,<把本地图片转成的 base64 粘到这里>";

        // 方式一：单图
        holder.addHistory(ChatMessage.withUser("这张图片里有什么？", imageUrlOrDataUri));

        // 方式二：多图对比（可变参数，可传多张）
        // holder.addHistory(ChatMessage.withUser("请对比这两张图", url1, url2));

        BasicClient.ClientResponse response = client.chat(null);
        System.out.println("图片分析: " + response.getContent());
    }

    // ==================== 主方法 ====================

    public static void main(String[] args) throws Exception {
        KernelRuntime rt = buildRuntime();
        try {
            run("示例1：基本同步对话", () -> basicChat(rt));
            run("示例2：使用工具进行对话", () -> chatWithTools(rt));
            run("示例3：流式对话（事件监听）", () -> streamChat(rt));
            run("示例4：事件钩子 EventHook", () -> chatWithEventHook(rt));
            run("示例5：历史持久化 PersistencePort", () -> chatWithHistoryPersist(rt));
            run("示例6：BasicClient 单次调用", () -> basicClientDemo(rt));
            run("示例7：自定义请求参数", () -> customApiParams(rt));
            run("示例8：多模态消息", () -> multimodalChat(rt));
        } finally {
            rt.close();
            // 流式对话（chatStream）跑在进程级共享线程池上，且该池非 daemon；
            // 独立示例运行结束时显式关闭，进程才会正常退出（宿主服务常驻时无需关）。
            net.itzq.mira.modules.ai.utils.ThreadPoolUtil.getExecutorService().shutdown();
        }
    }

    /** 逐段运行；缺模型/网络时打印提示并跳过，不影响其它段与阅读 */
    private static void run(String title, ThrowingRunnable action) {
        System.out.println("\n========== " + title + " ==========");
        try {
            action.run();
        } catch (Throwable t) {
            System.out.println("(本段需要可用模型/网络，已跳过: " + t.getMessage() + ")");
        }
    }

    /** 允许抛异常的动作（示例内部用） */
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
