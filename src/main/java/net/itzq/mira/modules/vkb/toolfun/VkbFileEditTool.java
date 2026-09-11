package net.itzq.mira.modules.vkb.toolfun;

import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.tool.annotation.Tool;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
import net.itzq.mira.modules.vkb.SessionKB;
import net.itzq.mira.modules.vkb.VKBConstants;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VKB 文件编辑工具 - 精确字符串替换
 *
 * @author tangzq
 */
public class VkbFileEditTool {

    private static final Logger log = LoggerFactory.getLogger(VkbFileEditTool.class);

    @Tool(name = VKBConstants.TOOL_FILE_EDIT,
            description = "在知识库文件中执行精确字符串替换。\n\n"
                    + "使用说明：\n"
                    + "- 编辑前必须先用 vkb_file_read 工具读取文件\n"
                    + "- old_string 必须在文件中唯一（除非 replace_all=true）\n"
                    + "- 使用 replace_all=true 可替换所有匹配项")
    public String fileEdit(
            @ToolParam(description = "要编辑的文件绝对路径（必填）") String filePath,
            @ToolParam(description = "要被替换的文本字符串（必填）") String oldString,
            @ToolParam(description = "替换后的新文本字符串（必填）") String newString,
            @ToolParam(description = "是否替换所有匹配项，默认 false", required = false) Boolean replaceAll,
            AgentContextHolder contextHolder) {

        try {
            SessionKB kb = getSessionKB(contextHolder);
            if (kb == null) {
                return "错误: 知识库未初始化";
            }

            // 验证
            if (oldString.equals(newString)) {
                return "编辑失败: old_string 和 new_string 相同";
            }
            if (StringUtils.isBlank(oldString)) {
                return "编辑失败: old_string 不能为空";
            }

            FileSystem fs = kb.getFileSystem();
            Path path = fs.getPath(filePath);

            if (!Files.exists(path)) {
                return "编辑失败: 文件不存在: " + filePath;
            }

            // 读取文件
            String content = kb.getKnowledgeTextByPath(filePath);

            String fileContent = content.replace("\r\n", "\n");
            boolean hasCRLF = content.contains("\r\n");

            // 归一化输入
            oldString = oldString.replace("\r\n", "\n");
            newString = newString.replace("\r\n", "\n");

            // 检查是否存在
            if (!fileContent.contains(oldString)) {
                return String.format("编辑失败: old_string 在文件中未找到。\nold_string: \"%s\"",
                        truncate(oldString, 200));
            }

            // 唯一性检查
            boolean replaceAllFlag = replaceAll != null && replaceAll;
            int matchCount = countOccurrences(fileContent, oldString);
            if (matchCount > 1 && !replaceAllFlag) {
                return String.format("编辑失败: 找到 %d 处匹配，但 replace_all 为 false。\n"
                        + "要替换所有匹配项，请设置 replace_all=true。", matchCount);
            }

            // 执行替换
            String newContent;
            if (replaceAllFlag) {
                newContent = fileContent.replace(oldString, newString);
            } else {
                newContent = fileContent.replaceFirst(Pattern.quote(oldString),
                        Matcher.quoteReplacement(newString));
            }

            // 恢复换行符
            if (hasCRLF) {
                newContent = newContent.replace("\n", "\r\n");
            }

            // 写回文件
            Files.write(path, newContent.getBytes(StandardCharsets.UTF_8));

            return String.format("文件编辑成功: %s\n%d 处匹配已替换", filePath,
                    replaceAllFlag ? matchCount : 1);

        } catch (Exception e) {
            log.error("VKB 文件编辑失败", e);
            return "编辑失败: " + e.getMessage();
        }
    }

    private int countOccurrences(String text, String search) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(search, index)) != -1) {
            count++;
            index += search.length();
        }
        return count;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    private SessionKB getSessionKB(AgentContextHolder contextHolder) {
        Object kbObj = contextHolder.getTopTempVariables().get(VKBConstants.VAR_SESSION_KB);
        if (kbObj instanceof SessionKB) {
            return (SessionKB) kbObj;
        }
        return null;
    }
}
