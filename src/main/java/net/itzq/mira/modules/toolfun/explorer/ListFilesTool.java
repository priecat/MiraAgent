package net.itzq.mira.modules.toolfun.explorer;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.tool.annotation.Tool;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
import net.itzq.mira.modules.toolfun.ToolFun;
import net.itzq.mira.modules.workspace.Workspace;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * ListFilesTool - 目录列表工具（code-explorer子代理专用）
 *
 * 列出指定目录下的文件和子目录，支持忽略规则、深度限制与分页（offset）。
 */
@Slf4j
public class ListFilesTool {

    private static final int DEFAULT_MAX_DEPTH = 3;
    private static final int DEFAULT_MAX_RESULTS = 200;
    private static final int MAX_OFFSET = 100_000;

    private static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
            ".git", ".svn", ".hg", ".bzr", ".idea", ".vscode",
            "node_modules", "__pycache__", ".mvn", "target", "build", "dist", ".cache"
    ));

    @Tool(name = ToolFun.TOOL_LIST_FILES,
          display = "文件列表",
          description = "列出指定目录下的文件和子目录结构。\n"
                  + "用于快速了解项目或文件夹的组织方式。\n"
                  + "参数:\n"
                  + "- path: 要列出的目录路径（必填）\n"
                  + "- maxDepth: 递归深度，默认3层\n"
                  + "- maxResults: 最大返回条目数，默认200\n"
                  + "- offset: 偏移量，用于查看被截断的后续内容，默认0"
    )
    public String listFiles(
            @ToolParam(description = "要列出的目录绝对路径") String path,
            @ToolParam(description = "递归深度，默认3", required = false) Integer maxDepth,
            @ToolParam(description = "最大返回条目数，默认200", required = false) Integer maxResults,
            @ToolParam(description = "偏移量，用于查看被截断的后续内容，默认0", required = false) Integer offset,
            AgentContextHolder contextHolder) {

        try {
            String searchPath = resolvePath(path, contextHolder);
            if (searchPath == null) {
                return "错误：当前未设置默认工作空间，必须指定 path 参数，或向用户询问查找的根目录路径参数。";
            }

            Path rootPath = Paths.get(searchPath).toAbsolutePath().normalize();
            if (!Files.exists(rootPath)) {
                return "目录不存在: " + searchPath;
            }
            if (!Files.isDirectory(rootPath)) {
                return "路径不是目录: " + searchPath;
            }

            int depth = maxDepth != null ? Math.min(maxDepth, 10) : DEFAULT_MAX_DEPTH;
            int limit = maxResults != null ? Math.min(maxResults, 500) : DEFAULT_MAX_RESULTS;
            int skip = offset != null ? Math.min(Math.max(offset, 0), MAX_OFFSET) : 0;

            List<String> entries = new ArrayList<>();
            // counter[0]: 已遍历过的有效条目总数（不含被 EXCLUDED 跳过的目录）
            // hasMore[0]: 是否因达到 limit 而提前终止（即还有后续内容）
            final int[] counter = {0};
            final boolean[] hasMore = {false};

            Files.walkFileTree(rootPath, EnumSet.of(FileVisitOption.FOLLOW_LINKS), depth, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(rootPath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String dirName = dir.getFileName().toString();
                    if (EXCLUDED_DIRS.contains(dirName) || dirName.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // 已收集满本批次，标记还有更多并终止
                    if (entries.size() >= limit) {
                        hasMore[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    int idx = counter[0]++;
                    if (idx < skip) {
                        // 跳过 offset 之前的条目，但继续遍历以到达后续内容
                        return FileVisitResult.CONTINUE;
                    }
                    String relPath = rootPath.relativize(dir).toString().replace('\\', '/');
                    entries.add("[D] " + relPath + "/");
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (entries.size() >= limit) {
                        hasMore[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    int idx = counter[0]++;
                    if (idx < skip) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relPath = rootPath.relativize(file).toString().replace('\\', '/');
                    long size = attrs.size();
                    entries.add("[F] " + relPath + " (" + formatSize(size) + ")");
                    return FileVisitResult.CONTINUE;
                }
            });

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("目录: %s (本批次 %d 条目, 偏移 %d)\n", rootPath, entries.size(), skip));
            for (String e : entries) {
                sb.append(e).append("\n");
            }

            if (entries.isEmpty()) {
                if (skip > 0) {
                    sb.append(String.format("\n[无更多条目: 偏移 %d 已超出范围]", skip));
                } else {
                    sb.append("\n[目录为空或无可显示条目]");
                }
            } else if (hasMore[0]) {
                int nextOffset = skip + entries.size();
                sb.append(String.format("\n[结果已截断: 当前显示 %d - %d, 使用 offset=%d 查看下一批]",
                        skip, nextOffset - 1, nextOffset));
            } else {
                sb.append(String.format("\n[已显示全部剩余条目: 当前 %d - %d]",
                        skip, skip + entries.size() - 1));
            }
            return sb.toString();

        } catch (Exception e) {
            log.error("ListFilesTool 执行失败", e);
            return "列出目录失败: " + e.getMessage();
        }
    }

    static String resolvePath(String path, AgentContextHolder contextHolder) {
        if (StringUtils.isNotBlank(path)) {
            return path;
        }
        if (contextHolder != null && StringUtils.isNotBlank(contextHolder.getWorkspaceId())) {
            try (Workspace wk = Workspace.load(contextHolder.getWorkspaceId())) {
                return wk.getStorageRoot().toString();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
