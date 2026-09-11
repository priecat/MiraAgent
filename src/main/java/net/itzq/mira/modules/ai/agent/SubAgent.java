package net.itzq.mira.modules.ai.agent;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.core.utils.PropsMap;
import net.itzq.mira.core.utils.PromptLoader;
import net.itzq.mira.modules.ai.client.tool.FCUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

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
    private String promptTemplatePath;

    /** 子代理的独立上下文 */
    private AgentContextHolder subContext;

    /**
     * 创建子代理
     *
     * @param parentContext  父代理上下文（用于继承workspaceId、modelAlias等）
     * @param name           子代理名称
     * @param promptTemplatePath  系统提示词模板路径（classpath或文件系统）
     * @param params         模板参数（freemarker）
     */
    public SubAgent(AgentContextHolder parentContext, String name,
                    String promptTemplatePath, PropsMap params) {
        super(buildSubContext(parentContext), name);
        this.promptTemplatePath = promptTemplatePath;
        this.subContext = getContextHolder();

        // 设置父代理引用
        if (parentContext != null && parentContext.getTopAgent() != null) {
            this.subContext.setParentAgent(parentContext.getTopAgent());
            // 继承顶层Agent引用
            this.subContext.setTopAgent(parentContext.getTopAgent());
        }

        // 加载系统提示词
        loadPrompt(parentContext, params);

        log.info("SubAgent【{}】已创建，提示词: {}", name, promptTemplatePath);
    }

    /**
     * 构建子代理上下文（从父代理继承关键配置）
     */
    private static AgentContextHolder buildSubContext(AgentContextHolder parentContext) {
        AgentContextHolder.AgentContextHolderBuilder builder = AgentContextHolder.builder();

        if (parentContext != null) {
            // 继承模型配置
            builder.modelAlias(parentContext.getModelAlias());
            // 继承workspaceId
            builder.workspaceId(parentContext.getWorkspaceId());
            // 继承事件中心（用于事件传递）
            builder.eventCenter(parentContext.getEventCenter());
            builder.eventHook(parentContext.getEventHook());
            // 继承全局变量引用
            builder.globalVariables(parentContext.getGlobalVariables());
            // 独立的历史记录（不继承父代理历史）
            builder.history(new ArrayList<>());
            // 独立的工具列表
            builder.tools(new ArrayList<>());
        }

        return builder.build();
    }

    /**
     * 加载系统提示词
     */
    private void loadPrompt(AgentContextHolder parentContext, PropsMap params) {
        if (StringUtils.isBlank(promptTemplatePath)) {
            return;
        }

        String prompt;
        if (params != null && !params.isEmpty()) {
            prompt = PromptLoader.prompt(promptTemplatePath, params);
        } else {
            prompt = PromptLoader.prompt(promptTemplatePath);
        }

        if (StringUtils.isNotBlank(prompt)) {
            subContext.setPrompt(prompt);
            log.info("SubAgent【{}】已加载提示词模板: {}", getName(), promptTemplatePath);
        } else {
            log.warn("SubAgent【{}】提示词模板为空: {}", getName(), promptTemplatePath);
        }
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
        String result = chat(query);
        long cost = System.currentTimeMillis() - start;
        log.info("SubAgent【{}】任务完成，耗时: {}ms", getName(), cost);
        return result;
    }
}
