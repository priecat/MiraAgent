package net.itzq.mira.modules.ai.mcp;

import com.alibaba.fastjson2.JSONObject;

import java.util.List;

/**
 * MCP客户端接口，定义与MCP服务器通信的标准方法
 */
public interface McpClient {

    /**
     * 连接并初始化MCP服务器
     */
    void connect() throws Exception;

    /**
     * 列出服务器提供的所有工具
     */
    List<McpToolInfo> listTools() throws Exception;

    /**
     * 调用指定工具
     *
     * @param toolName 工具名称
     * @param arguments 参数JSON字符串
     * @return 调用结果
     */
    String callTool(String toolName, String arguments) throws Exception;

    /**
     * 关闭连接，释放资源
     */
    void disconnect();

    /**
     * 是否已连接
     */
    boolean isConnected();

    /**
     * 获取服务器配置
     */
    McpServerConfig getConfig();
}
