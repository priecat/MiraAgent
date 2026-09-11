package net.itzq.mira.modules.ai.mcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP预解析结果，包装configJson和发现的工具列表
 * 可序列化，用于AgentContextHolder存储
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpPrepared {
    /** MCP配置JSON字符串 */
    private String configJson;
    /** 发现的MCP工具列表 */
    private List<McpToolInfo> tools;

    public static McpPrepared empty() {
        return new McpPrepared("", new ArrayList<>());
    }
}
