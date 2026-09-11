# MiraAgent


## 项目简介

MiraAgent 是一款轻量级 Java AI Agent 框架，实现最基础核心能力，可以快速接入、灵活定制智能体应用。

### 特性：
- **动态加载**：全局单例配置，支持运行时动态变更
- **工具调用**：通过注解定义工具，支持自动发现和调用
- **事件驱动**：完整的事件系统，支持Agent生命周期的各个阶段
- **流式处理**：支持SSE（Server-Sent Events）流式响应
- **多模型支持**：兼容OpenAI API格式的AI模型
- **上下文管理**：可扩展的对话上下文管理

---

## 核心架构

### 1. Agent 系统 (Agent System)

- **BasicAgent**：核心Agent类，支持同步和流式两种对话模式，由While循环驱动
- **BasicClient**：支持同步和流式两种对话模式，由业务硬编码驱动
- **AgentContextHolder**：Agent上下文，管理对话历史、工具列表、全局变量等

### 2. 工具系统 (Tool System)

- **@Tool 注解**：标记工具方法
- **@ToolParam 注解**：定义工具参数
- **FCUtil**：工具发现和调用工具类
- 自动扫描类路径中的工具方法

### 3. 事件系统 (Event System)

- **EventCenter**：事件总线，管理事件监听器
- **EventListener**：事件监听器接口 
- 支持的事件类型：
  - `GeneralEvent`：通用SSE事件
  - `ChatInputEvent`：用户输入事件
  - `StepBeginEvent`/`StepEndEvent`：步骤开始/结束事件
  - `CallToolBeginEvent`/`CallToolEndEvent`：工具调用开始/结束事件
  - `ChatEndEvent`：对话结束事件
  - `ErrorEvent`：错误事件

### 4. 客户端系统 (Client System)

- **OpenAICompatibleChatService**：OpenAI兼容的聊天服务
- **ModelRegistrationConfig**：模型配置管理
- 支持自定义流式事件处理器

---

## 快速开始

### 环境要求

- Java 8+

### 添加依赖

```xml
<!-- 添加仓库 -->
<repositories>
    <repository>
        <id>itzq-repo</id>
        <url>https://maven.cnb.cool/itzq/repo/-/packages/</url>
    </repository>
</repositories>

<!-- 添加依赖 -->
<dependency>
    <groupId>net.itzq.mira</groupId>
    <artifactId>agent-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 基本使用示例

#### 1. 配置AI模型

```java

// 配置OpenAI模型
ModelRegistrationConfig openaiConfig = ModelRegistrationConfig.builder()
    .alias("gpt-4")
    .providerName("OpenAI")
    .apiModelName("gpt-4")
    .apiHost("https://api.openai.com")
    .apiEndpoint("/v1/chat/completions")
    .apiKey("your-api-key")
    .sseEventHandler(OpenAICompatibleStreamEventHandler.class)
    .build();

// 注册模型
ApiProviderManage.getInstance().registerModel(openaiConfig);
ApiProviderManage.getInstance().setDefaultModel("gpt-4");
```

#### 2. 创建Agent并对话
BasicAgent 自动执行
```java

// 创建Agent上下文
AgentContextHolder context = AgentContextHolder.builder()
    .prompt("你是一个AI助手")
    .modelAlias("gpt-4")
    .build();

// 创建Agent 
BasicAgent agent = new BasicAgent(context, "my-agent");

// 同步对话
String response = agent.chat("你好，请介绍一下你自己");
System.out.println(response);
```

BasicClient 手动执行
```java
 // 1. 初始化 Context 和 Client
 AgentContextHolder contextHolder = AgentContextHolder.builder().build();
 contextHolder.setTools(Arrays.asList("ToolName" )); // 添加工具 
 contextHolder.setModelAlias("gpt-4o");

 BasicClient client = new BasicClient(contextHolder);

 // 2. 发起第一次单次请求
 String userQuestion = "请帮我查看 /var/log/app.log 的最后10行";
 ClientResponse response = client.chat(userQuestion);

 // 3. 判断 AI 的响应是直接回复，还是要求调用工具
 if (response.hasToolCalls()) {
     // AI 要求调用工具
     System.out.println("AI 请求调用工具:");
     for (ToolCall toolCall : response.getToolCalls()) {
         String funcName = toolCall.getFunction().getName();
         String args = toolCall.getFunction().getArguments();
         System.out.println(" - 工具: " + funcName + ", 参数: " + args);
        
       
         // 手动执行工具（这里用 FCUtil 执行，可以替换为其他的本地逻辑）
         String toolResult = FCUtil.invoke(funcName, args, contextHolder);
         System.out.println("工具执行结果: " + toolResult);
        
         // 手动将工具执行结果添加到历史中
         contextHolder.addHistory(ChatMessage.withTool(toolResult, toolCall.getId()));
          
         // 或者 例如拒绝执行工具
         String denyMsg = "用户拒绝执行 " + funcName + " 工具";
         contextHolder.addHistory(ChatMessage.withTool(denyMsg, toolCall.getId()));
         
     }

     // 4. 将工具执行结果再次发给 AI（发起第二次单次请求）
     System.out.println("将工具结果发回给 AI 进行总结...");
     ClientResponse secondResponse = client.chat(null); // 传 null 表示不添加新的 user 消息，直接用历史继续
    
     System.out.println("AI 最终回复: " + secondResponse.getContent());

 } else {
    // AI 直接给出了文本回复，无需调用工具 
    System.out.println("AI 直接回复: " + response.getContent());
 }
 
```

#### 3. 定义自定义工具

```java

public class MyTools {
    
    @Tool(name = "get_weather", description = "获取指定城市的天气信息")
    public String getWeather(
            @ToolParam(description = "城市名称") String city,
            AgentContextHolder context) {
        // 实现天气查询逻辑
        return city + "今天天气晴朗，温度25°C";
    }
    
    @Tool(name = "calculate", description = "执行数学计算")
    public String calculate(
            @ToolParam(description = "数学表达式") String expression,
            AgentContextHolder context) {
        // 实现计算逻辑
        return "计算结果: " + evaluateExpression(expression);
    }
}
```

#### 4. 使用工具进行对话

```java
// 添加工具到上下文
context.addTools("get_weather", "calculate");

// 进行对话，AI会自动调用工具
String response = agent.chat("北京今天天气怎么样？");
System.out.println(response);
```

---

## 参考示例

### Agent配置

```java
AgentContextHolder context = AgentContextHolder.builder()
    .prompt("系统提示词")              // 系统提示
    .modelAlias("gpt-4")              // 使用的模型
    .tools(Arrays.asList("tool1", "tool2")) // 可用工具列表
    .history(chatHistory)              // 历史记录
    .eventCenter(eventCenter)               // 事件总线
    .eventHook(eventHook)          // 事件钩子
    .build();
```

### API请求参数

`ApiRequestParams`类封装了OpenAI兼容的API请求参数：

```java

// 创建请求参数
ApiRequestParams params = ApiRequestParams.builder().build();

// 设置基本参数
params.setModel("gpt-4");
params.setTemperature(0.7f);
params.setMaxTokens(1000);
params.setStream(true);

// 设置消息
List<ChatMessage> messages = new ArrayList<>();
messages.add(ChatMessage.withSystem("你是一个AI助手"));
messages.add(ChatMessage.withUser("你好"));
params.setMessages(messages);

// 设置工具
List<Tool> tools = new ArrayList<>();
// ... 添加工具
params.setTools(tools);

// 添加自定义参数
params.addCustomParam("custom_param", "value");

// 获取所有参数
Map<String, Object> allParams = params.getAllParams();
```

**支持的主要参数**：
- `model`：模型名称
- `messages`：消息列表
- `stream`：是否流式输出
- `temperature`：温度参数
- `max_tokens`：最大token数
- `tools`：工具列表
- `tool_choice`：工具选择策略
- `frequency_penalty`：频率惩罚
- `presence_penalty`：存在惩罚
- `top_p`：Top P采样
- `response_format`：响应格式
- `stream_options`：流式输出选项

### 聊天消息

`ChatMessage`类封装了聊天消息，支持多种消息类型：

```java

// 系统消息
ChatMessage systemMessage = ChatMessage.withSystem("你是一个AI助手");

// 用户消息
ChatMessage userMessage = ChatMessage.withUser("你好");

// 助手消息
ChatMessage assistantMessage = ChatMessage.withAssistant("你好！有什么可以帮助你的吗？");

// 工具消息
ChatMessage toolMessage = ChatMessage.withTool("工具执行结果", "tool-call-id");

// 带工具调用的助手消息
List<ToolCall> toolCalls = new ArrayList<>();
// ... 添加工具调用
ChatMessage assistantWithTools = ChatMessage.withAssistant(toolCalls);

// 多模态消息（支持图片）
ChatMessage multimodalMessage = ChatMessage.withUser("描述这张图片", "https://example.com/image.jpg");
```

**消息类型枚举** (`ChatMessageType`)：
- `SYSTEM`：系统消息，用于设置AI的行为
- `USER`：用户消息
- `ASSISTANT`：助手消息，可以是文本或工具调用
- `TOOL`：工具执行结果消息

**多模态内容** (`Content`类)：

```java

// 纯文本内容
Content textContent = Content.ofText("Hello World");

// 多模态内容（文本+图片）
List<Content.MultiModal> modals = Content.MultiModal.withMultiModal(
    "描述这张图片", 
    "https://example.com/image1.jpg",
    "https://example.com/image2.jpg"
);
Content multiModalContent = Content.ofMultiModals(modals);
```

### 响应封装

`AnsResponse`类封装了Agent响应信息：

```java

// 创建响应
AnsResponse response = new AnsResponse();
response.setType("ChatContent");
response.setAnswer("你好！有什么可以帮助你的吗？");
response.setHistoryId("history-123");
response.setAgentId("agent-456");
response.setAgentName("my-agent");
response.setRoundId("round-789");
response.setMsgId("msg-101");

// 设置额外信息
JSONObject info = new JSONObject();
info.put("tokens", 150);
info.put("model", "gpt-4");
response.setInfo(info);
```

**响应字段**：
- `type`：响应类型（如ChatContent、ChatReasonContent等）
- `answer`：回答内容
- `historyId`：历史记录ID
- `agentId`：Agent ID
- `parentAgentId`：父Agent ID（如果有）
- `agentName`：Agent 名称
- `roundId`：每当发起Chat时分配的循环组ID
- `msgId`：消息ID
- `info`：额外信息（JSON格式）

**响应类型枚举** (`AnsType`)：
- `ChatContent`：普通对话返回
- `ChatReasonContent`：思考对话返回
- `Complete`：结束标志
- `Reference`：引用
- `StepBegin`/`StepEnd`：步骤开始/结束
- `CallToolBegin`/`CallToolEnd`：工具调用开始/结束
- `ChatEnd`：对话结束
- `UserInput`：用户输入
- `Error`：错误
- `Progress`：进度
- `Tips`：提示

---

## 高级功能

### 1. 流式对话

```java

// 创建事件总线
EventCenter eventCenter = new EventCenter();

// 注册事件监听器
eventCenter.register(new EventListener() {
    @Override
    public void onEvent(GeneralEvent event) {
        System.out.println("收到事件: " + event.getData());
    }
    
    @Override
    public void onChatInput(ChatInputEvent event) {
        System.out.println("用户输入: " + event.getQuestion());
    }
    
    @Override
    public void onStepBegin(StepBeginEvent event) {
        System.out.println("步骤开始: " + event.getDeep());
    }
    
    @Override
    public void onStepEnd(StepEndEvent event) {
        System.out.println("步骤结束");
    }
    
    @Override
    public void onCallToolBegin(CallToolBeginEvent event) {
        System.out.println("开始调用工具: " + event.getToolCall().getFunction().getName());
    }
    
    @Override
    public void onCallToolEnd(CallToolEndEvent event) {
        System.out.println("工具调用完成");
    }
    
    @Override
    public void onChatEnd(ChatEndEvent event) {
        System.out.println("对话结束，成功: " + event.isSuccess());
    }
    
    @Override
    public void onError(ErrorEvent event) {
        System.out.println("发生错误: " + event.getThrowable().getMessage());
    }
});

// 设置事件总线到上下文
context.setEventCenter(eventCenter);

// 流式对话
CountDownLatch latch = agent.chatStream("请详细解释量子计算");
latch.await(); // 等待流式对话完成
```

### 2. 自定义流式事件处理器

 

 

#### 抽象类：StreamEventHandler (用于适配服务提供商接口)

```java

public class AdvancedStreamHandler extends StreamEventHandler {
  
    @Override
    public void onEvent(String eventType, String data, String id) {
        // 处理SSE事件
        System.out.println("事件类型: " + eventType);
        System.out.println("数据: " + data);
    }
    
    ...
}
```

#### 内置实现：OpenAICompatibleStreamEventHandler

```java

// 创建事件监听器
SseEventListener listener = new SseEventListener() {
    @Override
    public void onEvent(String eventType, String data, String id, StreamEventHandler handler) {
        System.out.println("收到事件: " + handler.getCurrStr());
    }
    
    @Override
    public void onComment(String comment, StreamEventHandler handler) {
        // 处理注释
    }
    
    @Override
    public void onComplete(StreamEventHandler handler) {
        System.out.println("流式传输完成");
        if (handler.needToolCall()) {
            System.out.println("需要工具调用: " + handler.getToolCalls());
        } else {
            System.out.println("回答: " + handler.getAnswerOutput());
        }
    }
    
    @Override
    public void onError(Throwable t, StreamEventHandler handler) {
        t.printStackTrace();
    }
};

// 创建处理器
OpenAICompatibleStreamEventHandler handler = new OpenAICompatibleStreamEventHandler(listener);

// 使用处理器进行流式调用
chatService.getResult(apiRequestParams, null, listener);
```

### 3. 历史记录持久化

```java

public class MyHistoryPersist extends AbstractHistoryPersist {
    
    @Override
    public void saveChatMessages(List<ChatMessage> chatMessages, AgentContextHolder context) {
        // 保存聊天记录到数据库或文件
        for (ChatMessage message : chatMessages) {
            System.out.println("保存消息: " + message.getRole() + " - " + message.getContent());
        }
    }
    
    @Override
    public void saveEventMessages(List<?> eventMessages) {
        // 保存事件消息
    }
}

// 使用历史记录持久化
MyHistoryPersist historyPersist = new MyHistoryPersist();
agent.setHistoryPersist(historyPersist);
```

### 4. 多Agent协作

```java
// 创建父Agent 
AgentContextHolder parentContext = AgentContextHolder.builder()
    .prompt("你是一个协调者，负责管理子任务")
    .modelAlias("gpt-4")
    .build();
parentContext.addTools("delegate_task");
BasicAgent parentAgent = new BasicAgent(parentContext, "parent");


// 使用工具调用子Agent 
@Tool(name = "delegate_task", description = "将任务委派给子Agent", subAgent = true)
public String delegateTask(
        @ToolParam(description = "任务描述") String task,
        AgentContextHolder parentContext) {
    
    // 创建子Agent 
    AgentContextHolder childContext = AgentContextHolder.builder()
        .prompt("你是一个专业的问题解决者")
        .modelAlias("gpt-4")
        .build();
    BasicAgent childAgent = new BasicAgent(childContext, "child");

    return childAgent.chat(task);
}
```

### 5. 流式输出聚合

`DefaultStreamChunkAggregator`类提供了流式输出聚合功能，用于将流式输出的JSON数组字符串聚合为完整的响应：

```java

// 流式输出的JSON数组字符串
String streamOutput = "[{\"type\":\"ChatContent\",\"msgId\":\"msg1\",\"answer\":\"Hello\"}, {\"type\":\"ChatContent\",\"msgId\":\"msg1\",\"answer\":\" World\"}]";

// 聚合流式输出
JSONArray aggregated = DefaultStreamChunkAggregator.aggregate(streamOutput);
// 结果: [{"type":"ChatContent","msgId":"msg1","answer":"Hello World"}]

// 或者返回字符串形式
String aggregatedStr = DefaultStreamChunkAggregator.aggregateToString(streamOutput);
```

**功能特性**：
- 按消息ID聚合相同消息的内容
- 保持原始消息顺序
- 支持ChatContent和ChatReasonContent类型
- 自动拼接answer字段

---

## 扩展开发

 
### 1. 自定义事件钩子

`EventHook`接口提供了事件钩子，允许在事件触发时执行自定义逻辑：

```java

public class CustomEventHook implements EventHook {
    
    @Override
    public void onEvent(GeneralEvent event) {
        System.out.println("收到事件: " + event.getData());
    }
    
    @Override
    public void onChatInput(ChatInputEvent event) {
        System.out.println("用户输入: " + event.getQuestion());
    }
    
    @Override
    public void onStepBegin(StepBeginEvent event) {
        System.out.println("步骤开始: " + event.getDeep());
    }
    
    @Override
    public void onStepEnd(StepEndEvent event) {
        System.out.println("步骤结束");
    }
    
    @Override
    public void onCallToolBegin(CallToolBeginEvent event) {
        System.out.println("开始调用工具: " + event.getToolCall().getFunction().getName());
    }
    
    @Override
    public void onCallToolEnd(CallToolEndEvent event) {
        System.out.println("工具调用完成: " + event.getEndMsg());
    }
    
    @Override
    public void onChatEnd(ChatEndEvent event) {
        System.out.println("对话结束，成功: " + event.isSuccess());
    }
    
    @Override
    public void onError(ErrorEvent event) {
        System.out.println("发生错误: " + event.getThrowable().getMessage());
    }
}

// 使用事件钩子
AgentContextHolder context = AgentContextHolder.builder()
    .eventHook(new CustomEventHook())
    .build();
```

### 2. 默认事件监听器

`DefaultEventListener`抽象类提供了默认的事件监听器实现：

```java

public class MyEventListener extends DefaultEventListener {
    
    @Override
    public void sseEmitterSend(AnsResponse response) {
        System.out.println("发送响应: " + response.getType() + " - " + response.getAnswer());
    }
    
    @Override
    public void onError(ErrorEvent event) {
        System.out.println("发生错误: " + event.getThrowable().getMessage());
    }
}

// 使用默认事件监听器
EventCenter eventCenter = new EventCenter();
eventCenter.register(new MyEventListener());

AgentContextHolder context = AgentContextHolder.builder()
    .eventCenter(eventCenter)
    .build();
```

**DefaultEventListener功能**：
- 自动构建AnsResponse对象
- 支持SSE发送和历史记录保存
- 处理所有事件类型并转换为标准响应格式

### 3. SSE发送接口

`IEmitter`接口定义了SSE发送的基本接口：

```java

public class MyEmitter implements IEmitter {
    
    @Override
    public void sseEmitterSend(Object response) throws Exception {
        if (response instanceof AnsResponse) {
            AnsResponse ans = (AnsResponse) response;
            System.out.println("发送SSE: " + ans.getType() + " - " + ans.getAnswer());
        }
    }
}
```
 

### 5. 工具预加载

```java
@Component
public class CustomToolRegistrar {
    
    @PostConstruct
    public void registerTools() {
        List<String> tools = FCUtil.preLoadAllTools();
        System.out.println("已注册工具: " + tools);
    }
}
```

---

## 工具函数库

### JSON修复工具

`JsonRepair`类提供了自动修复格式错误JSON的功能：

```java

// 自动修复JSON
String brokenJson = "{'name': 'test', 'value': 123,}";  // 单引号和尾逗号
String fixedJson = JsonRepair.autoFix(brokenJson);
// 结果: {"name":"test","value":123}
```

**功能特性**：
- 自动移除Markdown代码块标记
- 修复单引号为双引号
- 移除尾随逗号
- 修复未加引号的属性名
- 使用AI辅助修复复杂JSON错误

### 提示词加载工具

`PromptLoader`类提供了提示词模板加载功能：

```java

// 加载提示词模板
String prompt = PromptLoader.prompt("assets/prompt/plan/SimpleAgentSystemPrompt.md");

// 使用参数化模板
PropsMap params = new PropsMap();
params.put("taskDescription", "分析代码库结构");
String promptWithParams = PromptLoader.prompt("assets/prompt/plan/PlanAgentSystemPrompt.md", params);

// 创建Agent时使用
AgentContextHolder context = AgentContextHolder.builder()
    .prompt(prompt)
    .modelAlias("gpt-4")
    .build();
```

**功能特性**：
- 支持从文件系统和classpath加载模板
- 支持Freemarker模板语法
- 支持参数化模板
- 自动处理编码问题

---

## 线程上下文管理
 

### 线程池获取，必须使用此类获取线程池

项目使用TTL包装的线程池，确保ThreadLocal变量可以正确传递到子线程：

```java

public class ThreadPoolUtil {
    private static final ExecutorService pool = Executors.newFixedThreadPool(128);
    private static final ExecutorService ttlExecutorService = TtlExecutors.getTtlExecutorService(pool);
    
    public static ExecutorService getExecutorService() {
        return ttlExecutorService;
    }
}
```

### 使用场景

1. **异步工具调用**：确保工具方法可以访问正确的AgentContextHolder
2. **流式处理**：在流式响应处理中保持上下文
3. **多Agent协作**：在子Agent调用中传递父Agent上下文

### 缓存策略

- 工具定义缓存：`FCUtil.toolEntityMap`
- 全局变量缓存：`AgentContextHolder.globalVariables`
- 临时变量：`AgentContextHolder.tempVariables`

---

## 最佳实践

### 1. 工具设计原则

- 工具方法应该是无状态的
- 工具参数应该有清晰的描述
- 工具返回值应该是字符串格式
- 对于复杂操作，考虑拆分为多个工具

### 2. 错误处理

```java
@Tool(name = "safe_tool", description = "安全的工具示例")
public String safeTool(
        @ToolParam(description = "输入参数") String input,
        AgentContextHolder context) {
    try {
        // 业务逻辑
        return processInput(input);
    } catch (Exception e) {
        // 返回错误信息给AI
        return "工具执行失败: " + e.getMessage();
    }
}
```

### 3. 性能优化

- 使用流式处理减少响应延迟
- 合理设置最大循环次数（maxDepth）
- 避免在工具中执行耗时操作
- 使用异步处理长时间运行的任务

### 4. 安全考虑

- 验证工具输入参数
- 限制工具访问权限
- 不要在工具中暴露敏感信息 

---

## 示例项目

### 天气查询Agent 

```java
public class WeatherAgent {
    
    @Tool(name = "get_weather", description = "获取天气信息")
    public String getWeather(
            @ToolParam(description = "城市名称") String city,
            AgentContextHolder context) {
        // 调用天气API
        return fetchWeatherFromAPI(city);
    }
    
    @Tool(name = "get_forecast", description = "获取天气预报")
    public String getForecast(
            @ToolParam(description = "城市名称") String city,
            @ToolParam(description = "天数") int days,
            AgentContextHolder context) {
        // 调用天气预报API
        return fetchForecastFromAPI(city, days);
    }
    
    public static void main(String[] args) {
        // 配置和启动Agent 
        AgentContextHolder context = AgentContextHolder.builder()
            .prompt("你是一个天气助手，可以查询天气信息")
            .modelAlias("gpt-4")
            .addTools("get_weather", "get_forecast")
            .build();
        
        BasicAgent agent = new BasicAgent(context, "weather-agent");
        String response = agent.chat("北京明天天气怎么样？");
        System.out.println(response);
    }
}
```
 
 
---

## 许可证

本项目采用 MIT 许可证开源。

 
