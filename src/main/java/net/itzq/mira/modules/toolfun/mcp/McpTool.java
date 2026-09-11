package net.itzq.mira.modules.toolfun.mcp;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.tool.annotation.Tool;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
import net.itzq.mira.modules.ai.mcp.McpManager;
import net.itzq.mira.modules.ai.mcp.McpPrepared;
import net.itzq.mira.modules.toolfun.ToolFun;
import org.apache.commons.lang3.StringUtils;

/**
 * MCP工具网关，让AI通过function call调用MCP工具
 * AI通过mcp_call工具传入完整的工具名和参数，由McpManager路由到对应服务器
 */
@Slf4j
public class McpTool {

    @Tool(
            name = ToolFun.TOOL_MCP_CALL,
            display = "调用MCP工具",
            description = "调用MCP（Model Context Protocol）工具。" +
                    "MCP工具是外部服务器提供的专业工具，工具名格式为 mcp__{server}__{tool}。" +
                    "可通过<available_mcp_tools>列表查看所有可用的MCP工具及其参数说明。" +
                    "调用时传入完整的工具名和JSON格式参数。"
    )
    public String mcpCall(
            @ToolParam(description = "完整的MCP工具名，格式: mcp__{serverName}__{toolName}，例如 mcp__filesystem__read_file") String tool_name,
            @ToolParam(description = "工具参数，JSON格式字符串，具体参数请参考工具的inputSchema", required = false) String arguments,
            AgentContextHolder context
    ) {
        McpPrepared mcpConfig = context.getMcpConfig();
        if (mcpConfig == null || StringUtils.isBlank(mcpConfig.getConfigJson())) {
            return "本次对话未配置MCP";
        }

        log.info("AI调用MCP工具: {}", tool_name);
        return McpManager.callTool(mcpConfig.getConfigJson(), tool_name, arguments);
    }
}
