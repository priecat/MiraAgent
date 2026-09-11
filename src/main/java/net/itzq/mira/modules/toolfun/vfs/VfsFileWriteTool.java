package net.itzq.mira.modules.toolfun.vfs;


import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.tool.annotation.Tool;
import net.itzq.mira.modules.ai.tool.annotation.ToolParam;
import net.itzq.mira.modules.toolfun.ToolFun;
import net.itzq.mira.modules.vfs.VFS;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;


/**
 * VfsFileWriteTool - 全量文件写入工具（基于内存 Var_VFS）
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
public class VfsFileWriteTool {

    @Tool(name = ToolFun.Tool_VFS_File_Write,
          display = "创建文件",
          description = "将文件写入虚拟文件系统。\n\n"
                  + "使用说明：\n"
                  + "- 此工具将覆盖目标路径上已有的文件\n"
                  + "- 如果是已有文件，必须先使用 "+ToolFun.Tool_VFS_File_Read +" 工具读取\n"
                  + "- 修改已有文件时优先使用 "+ToolFun.Tool_VFS_File_Edit +" 工具（仅发送 diff），仅在新建文件或完全重写时才使用 "+ToolFun.Tool_VFS_File_Write
                  +"\n"
                  + "- 除非用户明确要求，否则不要创建文档文件 (*.md) 或 README\n"
                  + "- 仅当用户明确要求时才使用 emoji")
    public String fileWrite(
            @ToolParam(description = "要写入的文件绝对路径（必填）") String filePath,
            @ToolParam(description = "要写入的完整文件内容（必填）") String content,
            AgentContextHolder contextHolder) {

        if (StringUtils.isBlank(contextHolder.getVfsId())){
            return "写入失败: 虚拟文件系统未初始化";
        }

        try (VFS vfs = VFS.load(contextHolder.getVfsId())) {

            boolean exists = vfs.exists(filePath);

            // 自动创建父目录（由 Var_VFS.write 内部处理，这里显式创建以保证逻辑清晰）
            int lastSlash = filePath.lastIndexOf('/');
            if (lastSlash > 0) {
                String parentDir = filePath.substring(0, lastSlash);
                if (!vfs.exists(parentDir)) {
                    vfs.createDirectories(parentDir);
                }
            }

            // CRLF → LF 归一化
            String normalizedContent = content.replace("\r\n", "\n");

            // 写入文件
            vfs.write(filePath, normalizedContent);

            // 统计
            long fileSize = vfs.size(filePath);
            int lineCount = normalizedContent.split("\n", -1).length;
            String action = exists ? "已覆盖" : "已创建";

            return String.format("✅ 文件%s: %s\n%d 行，%d bytes",
                    action, filePath, lineCount, fileSize);

        } catch (IOException e) {
            log.error("VfsFileWriteTool 执行失败", e);
            return "文件写入失败: " + e.getMessage();
        }
    }
}
