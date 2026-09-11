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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VKB Grep 工具 - 在虚拟工作空间文件中搜索正则表达式
 *
 * @author tangzq
 */
public class VkbGrepTool {

    private static final Logger log = LoggerFactory.getLogger(VkbGrepTool.class);
    private static final int DEFAULT_CONTEXT_LINES = 3;

    @Tool(name = ToolFun.TOOL_VKB_GREP,
            description = "在“工作空间”文件中使用正则表达式搜索内容。\n\n"
                    + "使用说明：\n"
                    + "- 支持完整正则表达式语法\n"
                    + "- 可指定文件路径或搜索整个“工作空间”\n"
                    + "- 返回匹配行及上下文\n"
                    + "- 适合在定位到相关文件后，精确查找内容")
    public String grep(
            @ToolParam(description = "正则表达式搜索模式（必填）") String pattern,
            @ToolParam(description = "文件路径（可选，不填则搜索所有文件）") String filePath,
            @ToolParam(description = "上下文行数，默认 3") Integer contextLines,
            AgentContextHolder contextHolder) {


        String sessionId = (String) contextHolder.getTopTempVariables().get(VKBConstants.VAR_SESSION_KB);
        if (StringUtils.isBlank(sessionId)) {
            sessionId = contextHolder.getHistoryId();
        }
        if (StringUtils.isBlank(sessionId)) {
            return "错误: “工作空间”未初始化";
        }



        try (VKB kb = VKB.load(sessionId)) {


            int ctxLines = contextLines != null ? contextLines : DEFAULT_CONTEXT_LINES;

            // 编译正则
            Pattern regex;
            try {
                regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            } catch (Exception e) {
                return "正则表达式语法错误: " + e.getMessage();
            }

            StringBuilder sb = new StringBuilder();
            int totalMatches = 0;

            if (StringUtils.isNotBlank(filePath)) {
                // 在指定文件中搜索
                String content = kb.readString(filePath);
                if (content == null) {
                    return "文件不存在: " + filePath;
                }
                totalMatches = grepInContent(sb, regex, content, filePath, ctxLines);
            } else {
                // 在所有文件中搜索
                List<SearchResult> grepResults = kb.grep(pattern,10);
                if (grepResults.isEmpty()) {
                    return "未找到匹配 '" + pattern + "' 的内容";
                }
                for (SearchResult result : grepResults) {
                    String content = kb.readString(result.getFilePath());
                    if (content != null) {
                        sb.append("=== ").append(result.getFilePath()).append(" ===\n");
                        totalMatches += grepInContent(sb, regex, content, result.getFilePath(), ctxLines);
                        sb.append("\n");
                    }
                }
            }

            if (totalMatches == 0) {
                return "未找到匹配 '" + pattern + "' 的内容";
            }

            sb.insert(0, String.format("找到 %d 处匹配:\n\n", totalMatches));
            return sb.toString();

        } catch (Exception e) {
            log.error("VKB Grep 失败", e);
            return "搜索失败: " + e.getMessage();
        }
    }

    private int grepInContent(StringBuilder sb, Pattern regex, String content,
                               String filePath, int ctxLines) {
        String[] lines = content.split("\n", -1);
        int matches = 0;

        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = regex.matcher(lines[i]);
            if (matcher.find()) {
                matches++;

                // 输出上下文
                int start = Math.max(0, i - ctxLines);
                int end = Math.min(lines.length - 1, i + ctxLines);

                if (matches > 1) sb.append("--\n");

                for (int j = start; j <= end; j++) {
                    String marker = (j == i) ? ":" : "-";
                    sb.append(String.format("%6d%s %s\n", j + 1, marker, lines[j]));
                }
            }
        }

        return matches;
    }

    private SessionKB getSessionKB(AgentContextHolder contextHolder) {
        Object kbObj = contextHolder.getTopTempVariables().get(VKBConstants.VAR_SESSION_KB);
        if (kbObj instanceof SessionKB) {
            return (SessionKB) kbObj;
        }
        return null;
    }
}
