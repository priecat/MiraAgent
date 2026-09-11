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
import java.util.Arrays;
import java.util.List;

/**
 * VKB 文件读取工具 - 读取知识库中的文件内容
 *
 * @author tangzq
 */
public class VkbFileReadTool {

    private static final Logger log = LoggerFactory.getLogger(VkbFileReadTool.class);
    private static final int DEFAULT_LIMIT = 2000;
    private static final int MAX_OUTPUT_SIZE_BYTES = 256 * 1024;

    @Tool(name = VKBConstants.TOOL_FILE_READ,
            description = "从知识库虚拟文件系统读取文件内容。\n\n"
                    + "使用说明：\n"
                    + "- file_path 参数必须是绝对路径（如 /docs/report.md）\n"
                    + "- 默认从文件开头读取最多 2000 行\n"
                    + "- 使用 offset 和 limit 参数读取指定范围\n"
                    + "- 读取的是转换后的纯文本内容（非源文件）")
    public String fileRead(
            @ToolParam(description = "要读取的文件绝对路径（必填）") String filePath,
            @ToolParam(description = "起始行号（从 1 开始），默认为 1", required = false) Integer offset,
            @ToolParam(description = "读取行数上限，默认 2000", required = false) Integer limit,
            AgentContextHolder contextHolder) {

        try {
            SessionKB kb = getSessionKB(contextHolder);
            if (kb == null) {
                return "错误: 知识库未初始化";
            }

            FileSystem fs = kb.getFileSystem();
            Path path = fs.getPath(filePath);

            // 文件存在性检查
            if (!Files.exists(path)) {
                return "文件不存在: " + filePath;
            }

            // 检查是否为目录
            if (Files.isDirectory(path)) {
                return "读取失败: 指定路径是一个目录: " + filePath;
            }

            // 读取内容
            String content = kb.getKnowledgeTextByPath(filePath);

            if (StringUtils.isBlank(content)) {
                return "文件为空: " + filePath;
            }

            // 按行分割
            String normalized = content.replace("\r\n", "\n");
            List<String> allLines = Arrays.asList(normalized.split("\n", -1));
            int totalLines = allLines.size();

            int startLine = Math.max(0, (offset != null ? offset : 1) - 1);
            int maxLines = limit != null ? limit : DEFAULT_LIMIT;
            int endLine = Math.min(totalLines, startLine + maxLines);

            List<String> selectedLines = allLines.subList(startLine, endLine);

            // 格式化输出
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("文件: %s (共 %d 行，显示第 %d-%d 行)\n\n",
                    filePath, totalLines, startLine + 1, endLine));

            for (int i = 0; i < selectedLines.size(); i++) {
                sb.append(String.format("%6d| %s\n", startLine + i + 1, selectedLines.get(i)));
            }

            // 结果大小检查
            int outputBytes = sb.toString().getBytes(StandardCharsets.UTF_8).length;
            if (outputBytes > MAX_OUTPUT_SIZE_BYTES) {
                return String.format("文件输出超过限制 (%d > %d bytes)。请使用 offset/limit 缩小范围。",
                        outputBytes, MAX_OUTPUT_SIZE_BYTES);
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("VKB 文件读取失败", e);
            return "文件读取失败: " + e.getMessage();
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
