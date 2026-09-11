package net.itzq.mira.modules.toolfun.vkb;

import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.tool.annotation.Tool;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
import net.itzq.mira.modules.toolfun.ToolFun;
import net.itzq.mira.modules.vkb.SessionKB;
import net.itzq.mira.modules.vkb.VKB;
import net.itzq.mira.modules.vkb.VKBConstants;
import net.itzq.mira.modules.vkb.model.SearchResult;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * VKB 搜索工具 - 向量搜索返回相关文件列表
 *
 * @author tangzq
 */
public class VkbSearchTool {

    private static final Logger log = LoggerFactory.getLogger(VkbSearchTool.class);

    @Tool(name = ToolFun.TOOL_VKB_SEARCH,
          description = "在“工作空间”中搜索相关文件。\n\n"
                  + "使用说明：\n"
                  + "- 返回与查询相关的文件列表（文件路径 + 相似度）\n"
                  + "- 使用向量搜索定位相关文件\n"
                  + "- 返回文件路径后，可用 vkb_grep 或 vkb_file_read 获取具体内容\n"
                  + "- 适合先定位文件，再精确查找答案")
    public String search(@ToolParam(description = "查询关键词（必填）") String query, AgentContextHolder contextHolder) {

        String sessionId = (String) contextHolder.getTopTempVariables().get(VKBConstants.VAR_SESSION_KB);
        if (StringUtils.isBlank(sessionId)) {
            sessionId = contextHolder.getHistoryId();
        }
        if (StringUtils.isBlank(sessionId)) {
            return "错误: “工作空间”未初始化";
        }

        try (VKB kb = VKB.load(sessionId)) {

            List<SearchResult> results = kb.search(query, 10);

            if (results.isEmpty()) {
                return "未找到与 '" + query + "' 相关的文件";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("搜索 '%s' 找到 %d 个相关文件:\n\n", query, results.size()));

            for (int i = 0; i < results.size(); i++) {
                SearchResult result = results.get(i);
                sb.append(String.format("%d. %s\n", i + 1, result.getFilePath()));
                sb.append(String.format("   文件名: %s\n", result.getFileName()));
                sb.append(String.format("   相似度: %.4f\n", result.getScore()));
                sb.append(String.format("   来源: %s\n", result.getSource()));
                if (!result.getMatchedChunks().isEmpty()) {
                    sb.append(String.format("   匹配分段: %d 个\n", result.getMatchedChunks().size()));
                }
                sb.append("\n");
            }

            sb.append("---\n");
            sb.append("提示: 使用 vkb_grep 搜索具体内容，或 vkb_file_read 读取文件");

            return sb.toString();

        } catch (Exception e) {
            log.error("VKB 搜索失败", e);
            return "搜索失败: " + e.getMessage();
        }
    }

    private SessionKB getSessionKB(AgentContextHolder contextHolder) {
        Object kbObj = contextHolder.getTopTempVariables().get(VKBConstants.VAR_SESSION_KB);
        if (kbObj instanceof SessionKB) {
            return (SessionKB) kbObj;
        }
        return null;
    }
}
