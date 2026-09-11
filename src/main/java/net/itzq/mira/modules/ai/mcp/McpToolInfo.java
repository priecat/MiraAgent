package net.itzq.mira.modules.ai.mcp;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP工具信息，从MCP服务器动态发现
 */
@Data
public class McpToolInfo {

    /** 工具名称（来自MCP服务器） */
    private String name;

    /** 工具描述 */
    private String description;

    /** 参数JSON Schema */
    private String inputSchema;

    /** 所属MCP服务器名称 */
    private String serverName;

    /** 完整的工具调用名称: mcp__{serverName}__{toolName} */
    private String fullName;

    public McpToolInfo() {
    }

    public McpToolInfo(String serverName, String name, String description, String inputSchema) {
        this.serverName = serverName;
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.fullName = "mcp__" + serverName + "__" + name;
    }

    /**
     * 生成用于系统提示词的工具摘要
     */
    public String toSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("- **").append(fullName).append("**");
        if (description != null && !description.isEmpty()) {
            String firstLine = description.split("\n")[0].trim();
            if (firstLine.length() > 120) {
                firstLine = firstLine.substring(0, 117) + "...";
            }
            sb.append(": ").append(firstLine);
        }
        sb.append(" (server: ").append(serverName).append(")");
        return sb.toString();
    }
}
