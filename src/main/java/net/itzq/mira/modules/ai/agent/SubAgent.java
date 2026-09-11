package net.itzq.mira.modules.ai.agent;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.client.openai.chat.entity.ChatMessage;
import net.itzq.mira.modules.ai.tool.FCUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * SubAgent - 子代理基类
 *
 * 子代理拥有独立的上下文（history、tools、prompt），与主代理隔离。
 * 子代理执行完毕后，仅将最终结果返回给主代理，不污染主代理上下文。
 *
 * 使用方式：
 *   SubAgent subAgent = new SubAgent(parentContext, "code-explorer", promptPath, params);
 *   subAgent.addTools("list_files", "search_file", "search_content", "read_file");
 *   String result = subAgent.chat(query);
 *
 * @author tangzq
 */
@Slf4j
public class SubAgent extends BasicAgent {

    /** 子代理系统提示词模板路径 */
    private String systemPrompt;

    /** 子代理的独立上下文 */
    private AgentContextHolder subContext;

    /**
     * 创建子代理
     *
     * @param parentContext  父代理上下文（用于继承workspaceId、modelAlias等）
     * @param name           子代理名称
     */
    public SubAgent(AgentContextHolder parentContext, String name,
                    String systemPrompt) {

        super(buildSubContext(parentContext), name);

        this.systemPrompt = systemPrompt;
        this.subContext = getContextHolder();

        // 设置父代理引用
        if (parentContext != null && parentContext.getTopAgent() != null) {
            this.subContext.setParentAgent(parentContext.getTopAgent());
            // 继承顶层Agent引用
            this.subContext.setTopAgent(parentContext.getTopAgent());
        }

        // 加载系统提示词
        subContext.setPrompt(this.systemPrompt);

        log.info("SubAgent【{}】已创建", name);
    }

    /**
     * 构建子代理上下文（从父代理继承关键配置）
     */
    private static AgentContextHolder buildSubContext(AgentContextHolder parentContext) {
        AgentContextHolder.AgentContextHolderBuilder builder = AgentContextHolder.builder();

        if (parentContext != null) {
            builder.topAgent(parentContext.getTopAgent());
            // 继承模型配置
            builder.modelAlias(parentContext.getModelAlias());
            // 继承workspacePath
            builder.workspacePath(parentContext.getTopWorkspacePath());
            // 继承事件中心（用于事件传递）
            builder.eventCenter(parentContext.getEventCenter());
            builder.eventHook(parentContext.getEventHook());
            // 继承全局变量引用
            builder.globalVariables(parentContext.getGlobalVariables());
            // 独立的历史记录（不继承父代理历史）
            builder.history(new ArrayList<>());
            // 独立的工具列表
            builder.tools(new ArrayList<>());
            // 继承 VFS
            builder.vfsId(parentContext.getTopVfsId());
        }

        return builder.build();
    }

    /**
     * 添加工具到子代理
     */
    public SubAgent addSubTools(String... toolNames) {
        if (toolNames != null) {
            for (String tool : toolNames) {
                subContext.addTools(tool);
            }
        }
        return this;
    }

    /**
     * 注册工具类到FCUtil（确保工具方法可用）
     */
    public SubAgent registerToolClasses(Class<?>... toolClasses) {
        try {
            FCUtil.scanTools(toolClasses);
        } catch (Exception e) {
            log.error("注册工具类失败", e);
        }
        return this;
    }

    /**
     * 设置最大循环次数
     */
    public SubAgent withMaxDepth(int depth) {
        super.setMaxDepth(depth);
        return this;
    }

    /**
     * 执行子代理任务
     *
     * @param query 任务描述
     * @return 子代理的最终回复
     */
    public String execute(String query) {
        log.info("SubAgent【{}】开始执行任务: {}", getName(), query.length() > 100 ? query.substring(0, 100) + "..." : query);
        long start = System.currentTimeMillis();
        CountDownLatch countDownLatch = chatStream(query);
        long cost = System.currentTimeMillis() - start;

        try {
            countDownLatch.await(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            return "执行失败：执行超时";
        }
        log.info("SubAgent【{}】任务完成，耗时: {}ms", getName(), cost);

        List<ChatMessage> history = getContextHolder().getHistory();
        if (history.size() == 0) {
            return "执行失败：无消息返回";
        }
        ChatMessage lastMessage = history.get(history.size() - 1);
        if (lastMessage != null && lastMessage.getContent() != null) {
            if ("assistant".equals(lastMessage.getRole())) {
                return lastMessage.getContent().getText();
            }
        }

        return "执行失败：未成功获取执行结果";
    }
}
