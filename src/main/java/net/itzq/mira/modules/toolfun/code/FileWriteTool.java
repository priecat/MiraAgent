package net.itzq.mira.modules.toolfun.code;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.tool.annotation.Tool;
import net.itzq.mira.modules.ai.tool.annotation.ToolParam;
import net.itzq.mira.modules.ai.tool.ToolCallResult;
import net.itzq.mira.modules.toolfun.ToolFun;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static net.itzq.mira.modules.toolfun.code.FileReadTool.BLOCKED_PATHS;

/**
 * FileWriteTool - 全量文件写入工具（编码/换行保真版）
 *
 * - 覆盖已存在的文件；覆盖前应先 Read
 * - 覆盖已有文件时保留其字符集 + BOM + 换行风格（避免把 UTF-16 / GBK / CRLF 文件写坏）
 * - 新建文件默认 UTF-8（无 BOM）、LF
 * - 优先使用 Edit 工具修改已有文件（仅发 diff），Write 仅用于新建或完全重写
 * - 除非用户明确要求，否则不创建文档文件 (*.md) 或 README
 *
 * @author tangzq
 */
@Slf4j
public class FileWriteTool {

    @Tool(name = ToolFun.TOOL_Write,
          display = "创建文件",
          description = "将文件写入本地文件系统。\n\n"
                    + "使用说明：\n"
                    + "- 此工具将覆盖目标路径上已有的文件\n"
                    + "- 如果是已有文件，必须先使用 Read 工具读取\n"
                    + "- 覆盖已有文件时会保留其原有编码与换行风格；新建文件默认 UTF-8 + LF\n"
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
                    return ToolCallResult.error("安全限制: 无法写入设备文件或特殊文件: " + filePath);
                }
            }

            boolean exists = Files.exists(path);
            if (exists && Files.isDirectory(path)) {
                return ToolCallResult.error("写入失败: 目标是一个目录 —— " + filePath);
            }

            // 入参自身带的 BOM（有些模型会把 \uFEFF 一起写进 content）
            String incoming = content;
            boolean incomingBom = false;
            if (incoming.startsWith("\uFEFF")) {
                incoming = incoming.substring(1);
                incomingBom = true;
            }

            // 解析目标文件的既有编码风格：存在且可解码则沿用，否则按新建处理
            Charset charset = StandardCharsets.UTF_8;
            String lineEndings = TextFileCodec.EOL_LF;
            byte[] bom = null;
            boolean overwritingUndecodable = false;

            if (exists) {
                TextFileCodec.Decoded decoded = null;
                try {
                    decoded = TextFileCodec.decode(Files.readAllBytes(path));
                } catch (IOException e) {
                    log.warn("FileWriteTool: 读取原文件失败，将按 UTF-8 写入 —— {}", e.getMessage());
                }
                if (decoded != null) {
                    charset = decoded.charset;
                    lineEndings = decoded.lineEndings;
                    bom = decoded.bom;
                } else {
                    // 原文件不是可识别的文本（二进制/未知编码）：仍允许覆盖，但明确告知
                    overwritingUndecodable = true;
                }
            }

            // 入参带 BOM 而目标原本没有 BOM 时补上（ desiredBom 策略）
            if (bom == null && incomingBom) {
                bom = bomFor(charset);
            }

            // LF 归一后按目标风格写回
            String normalized = TextFileCodec.normalizeLineEndings(incoming);
            String output = TextFileCodec.restoreLineEndings(normalized, lineEndings);

            // 创建父目录
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.write(path, TextFileCodec.encode(output, charset, bom));
            File file = path.toFile();
            file.setExecutable(true, false);
            file.setReadable(true, false);
            file.setWritable(true, false);

            long fileSize = Files.size(path);
            int lineCount = normalized.split("\n", -1).length;

            StringBuilder result = new StringBuilder();
            result.append(String.format("文件%s: %s\n%d 行，%d bytes（编码 %s%s，换行 %s%s）",
                    exists ? "已覆盖" : "已创建", filePath, lineCount, fileSize,
                    charset.name(),
                    bom != null ? " + BOM" : "",
                    lineEndings,
                    overwritingUndecodable ? " — 注意: 原文件不是可识别的文本，已按当前编码整体覆盖" : ""));
            return ToolCallResult.successUnlessMarked(result.toString());

        } catch (IOException e) {
            log.error("FileWriteTool 执行失败", e);
            return ToolCallResult.error("文件写入失败: " + e.getMessage());
        }
    }

    /** 给定字符集对应的 BOM；UTF-8 之外返回 null（不擅自给 UTF-16 加 BOM） */
    private byte[] bomFor(Charset charset) {
        if (StandardCharsets.UTF_8.equals(charset)) {
            return TextFileCodec.BOM_UTF8;
        }
        return null;
    }
}
