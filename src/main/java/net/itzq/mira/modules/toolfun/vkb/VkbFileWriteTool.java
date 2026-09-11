package net.itzq.mira.modules.toolfun.vkb;

import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.tool.annotation.Tool;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
import net.itzq.mira.modules.toolfun.ToolFun;
import net.itzq.mira.modules.vkb.SessionKB;
import net.itzq.mira.modules.vkb.VKB;
import net.itzq.mira.modules.vkb.VKBConstants;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * VKB 文件写入工具 - 写入文件到虚拟工作空间
 *
 * @author tangzq
 */
public class VkbFileWriteTool {

    private static final Logger log = LoggerFactory.getLogger(VkbFileWriteTool.class);

    @Tool(name = ToolFun.TOOL_VKB_FILE_WRITE,
            description = "将文件写入“工作空间”文件系统。\n\n"
                    + "使用说明：\n"
                    + "- 此工具将覆盖目标路径上已有的文件\n"
                    + "- 如果是已有文件，必须先使用 vkb_file_read 工具读取\n"
                    + "- 修改已有文件时优先使用 vkb_file_edit 工具")
    public String fileWrite(@ToolParam(description = "要写入的文件绝对路径（必填）根目录：/ 例:/docs/demo.txt") String filePath,
            @ToolParam(description = "要写入的完整文件内容（必填）") String content, AgentContextHolder contextHolder) {

        String sessionId = (String) contextHolder.getTopTempVariables().get(VKBConstants.VAR_SESSION_KB);
        if (StringUtils.isBlank(sessionId)) {
            sessionId = contextHolder.getHistoryId();
        }
        if (StringUtils.isBlank(sessionId)) {
            return "错误: “工作空间”未初始化";
        }

        try (VKB kb = VKB.load(sessionId)) {
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
            kb.write(path.toString(), normalizedContent);

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
