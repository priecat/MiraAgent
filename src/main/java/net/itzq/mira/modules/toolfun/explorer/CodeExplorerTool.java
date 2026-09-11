package net.itzq.mira.modules.toolfun.explorer;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.core.utils.PromptLoader;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.agent.SubAgent;
import net.itzq.mira.modules.ai.client.tool.annotation.Tool;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
import net.itzq.mira.modules.toolfun.ToolFun;

/**
 * CodeExplorerTool - 代码探索子代理工具
 *
 * 主代理通过此工具委派代码探索任务给code-explorer子代理。
 * 子代理拥有独立上下文，使用 list_files/search_file/search_content/read_file 工具
 * 在代码库中进行高效搜索，仅将最终分析结果返回给主代理。
 *
 * 通过 @Tool(subAgent=true) 标记，子代理的返回结果会作为assistant消息注入主代理历史，
 * 而非普通的tool消息，使主代理能更自然地利用探索结果。
 */
@Slf4j
public class CodeExplorerTool {

    /** code-explorer 系统提示词模板路径 */
    private static final String PROMPT_PATH = "assets/prompt/code-explorer.md";

    @Tool(name = ToolFun.TOOL_CODE_EXPLORER,
          display = "探索工作空间",
          subAgent = true,
          description = "启动代码探索子代理，在代码库中进行广泛的搜索和探索。\n\n"
                  + "当任务需要广泛的代码库探索而非读取几个特定文件时使用此工具。\n"
                  + "子代理捆绑了 list_files、search_file、search_content、read_file 等工具，使大规模搜索更高效。\n\n"
                  + "使用场景：\n"
                  + "- 需要理解代码库或文件夹的结构\n"
                  + "- 识别模块、包或子项目\n"
                  + "- 查找某个功能、概念或行为的实现位置\n"
                  + "- 收集散布在多个文件中的信息\n"
                  + "- 形成项目组织方式的高层视图\n\n"
                  + "子代理的搜索不会进入主代理上下文，从而大大减少上下文大小和token用量。\n\n"
                  + "参数:\n"
                  + "- query: 探索任务描述（必填），需要详细说明要搜索什么、为什么搜索"
    )
    public String codeExplorer(
            @ToolParam(description = "探索任务描述，需详细说明要搜索的内容和目的。例如：\"在 src/main/java 下查找所有处理用户认证的类，列出类名和关键方法\"") String query,
            AgentContextHolder contextHolder) {

        try {
            log.info("启动 code-explorer 子代理，任务: {}", query.length() > 100 ? query.substring(0, 100) + "..." : query);

            String prompt = PromptLoader.prompt(PROMPT_PATH);

            // 创建子代理
            SubAgent explorer = new SubAgent(contextHolder, "code-explorer", prompt);

            // 添加explorer工具到子代理上下文
            explorer.addSubTools(
                    ToolFun.TOOL_LIST_FILES,
                    ToolFun.TOOL_Read,
                    ToolFun.TOOL_Glob,
                    ToolFun.TOOL_Grep
            );

            // 执行探索任务
            String result = explorer.execute(query);

            log.info("code-explorer 子代理完成");
            return result;

        } catch (Exception e) {
            log.error("code-explorer 子代理执行失败", e);
            return "探索失败: " + e.getMessage();
        }
    }
}
