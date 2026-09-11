package net.itzq.mira.modules.vkb.toolfun;

import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.tool.annotation.Tool;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
import net.itzq.mira.modules.vkb.SessionKB;
import net.itzq.mira.modules.vkb.VKBConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * VKB 文件写入工具 - 写入文件到知识库
 *
 * @author tangzq
 */
public class VkbFileWriteTool {

    private static final Logger log = LoggerFactory.getLogger(VkbFileWriteTool.class);

    @Tool(name = VKBConstants.TOOL_FILE_WRITE,
            description = "将文件写入知识库虚拟文件系统。\n\n"
                    + "使用说明：\n"
                    + "- 此工具将覆盖目标路径上已有的文件\n"
                    + "- 如果是已有文件，必须先使用 vkb_file_read 工具读取\n"
                    + "- 修改已有文件时优先使用 vkb_file_edit 工具")
    public String fileWrite(
            @ToolParam(description = "要写入的文件绝对路径（必填）根目录：/ 例:/docs/demo.txt") String filePath,
            @ToolParam(description = "要写入的完整文件内容（必填）") String content,
            AgentContextHolder contextHolder) {

        try {
            SessionKB kb = getSessionKB(contextHolder);
            if (kb == null) {
                return "错误: 知识库未初始化";
            }

            FileSystem fs = kb.getFileSystem();
            Path path = fs.getPath(filePath);

            // CRLF → LF 归一化
            String normalizedContent = content.replace("\r\n", "\n");

            // 创建父目录
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // 写入文件
            Files.write(path, normalizedContent.getBytes(StandardCharsets.UTF_8));

            // 统计
            int lineCount = normalizedContent.split("\n", -1).length;
            long fileSize = normalizedContent.getBytes(StandardCharsets.UTF_8).length;

            return String.format("文件已写入: %s\n%d 行，%d bytes", filePath, lineCount, fileSize);

        } catch (Exception e) {
            log.error("VKB 文件写入失败", e);
            return "文件写入失败: " + e.getMessage();
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
