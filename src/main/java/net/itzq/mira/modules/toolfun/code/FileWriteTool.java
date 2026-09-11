package net.itzq.mira.modules.toolfun.code;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.tool.annotation.Tool;
import net.itzq.mira.modules.ai.tool.annotation.ToolParam;
import net.itzq.mira.modules.toolfun.ToolFun;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static net.itzq.mira.modules.toolfun.code.FileReadTool.BLOCKED_PATHS;

/**
 * FileWriteTool - 全量文件写入工具
 *
 * - 覆盖已存在的文件
 * - 覆盖已有文件前必须先用 Read 工具读取
 * - 优先使用 Edit 工具修改已有文件（仅发 diff），Write 仅用于新建或完全重写
 * - 除非用户明确要求，否则不创建文档文件 (*.md) 或 README
 * - 换行符强制 LF
 *
 * @author tangzq
 */
@Slf4j
public class FileWriteTool {

    @Tool(name =  ToolFun.TOOL_Write,
          display = "创建文件",
          description = "将文件写入本地文件系统。\n\n"
                    + "使用说明：\n"
                    + "- 此工具将覆盖目标路径上已有的文件\n"
                    + "- 如果是已有文件，必须先使用 Read 工具读取\n"
                    + "- 修改已有文件时优先使用 Edit 工具（仅发送 diff），仅在新建文件或完全重写时才使用 Write\n"
                    + "- 除非用户明确要求，否则不要创建文档文件 (*.md) 或 README\n"
                    + "- 仅当用户明确要求时才使用 emoji\n"
                    + "- 不要通过 Bash 工具使用 echo/cat heredoc 来写入文件"
    )
    public String fileWrite(
            @ToolParam(description = "要写入的文件绝对路径（必填）") String filePath,
            @ToolParam(description = "要写入的完整文件内容（必填）") String content,
            AgentContextHolder contextHolder) {

        try {
            Path path = Paths.get(filePath).toAbsolutePath().normalize();

            // 安全检查：设备文件（含 /proc/*/fd/* 别名）
            String pathStr = path.toString();
            String fileName = path.getFileName().toString().toUpperCase();
            for (String blocked : BLOCKED_PATHS) {
                if (pathStr.contains(blocked) || fileName.equals(blocked)) {
                    return "安全限制: 无法读取设备文件或特殊文件: " + filePath;
                }
            }

            boolean exists = Files.exists(path);

            // 创建父目录
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // CRLF → LF 归一化
            String normalizedContent = content.replace("\r\n", "\n");

            // 写入文件
            Files.write(path, normalizedContent.getBytes(StandardCharsets.UTF_8));
            File file = path.toFile();
            file.setExecutable(true, false);
            file.setReadable(true, false);
            file.setWritable(true, false);

            // 统计
            long fileSize = Files.size(path);
            int lineCount = normalizedContent.split("\n", -1).length;
            String action = exists ? "已覆盖" : "已创建";

            return String.format("✅ 文件%s: %s\n%d 行，%d bytes",
                    action, filePath, lineCount, fileSize);

        } catch (IOException e) {
            log.error("FileWriteTool 执行失败", e);
            return "文件写入失败: " + e.getMessage();
        }
    }
}
