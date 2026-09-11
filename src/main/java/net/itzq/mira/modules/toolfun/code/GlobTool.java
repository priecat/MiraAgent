package net.itzq.mira.modules.toolfun.code;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.tool.annotation.Tool;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
import net.itzq.mira.modules.toolfun.ToolFun;
import net.itzq.mira.modules.workspace.Workspace;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

/**
 * GlobTool - 文件模式匹配工具（增强容错版）
 *
 * 针对小参数模型做了友好化处理：
 * - 自动识别 pattern 中的绝对路径并分离为 path 参数
 * - 强化描述，避免模型混淆
 * - 空结果时给出排查建议
 */
@Slf4j
public class GlobTool {

    private static final int DEFAULT_MAX_RESULTS = 100;

    /** 自动排除的目录 */
    private static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
            ".git", ".svn", ".hg", ".bzr", ".jj", ".sl", ".idea", ".vscode",
            "node_modules", "__pycache__", ".mvn", "target", "build", "dist", ".cache"
    ));

    // 匹配 Windows 绝对路径前缀（如 "D:/" 或 "D:\"）
    private static final Pattern WIN_ABSOLUTE_PREFIX =
            Pattern.compile("^[a-zA-Z]:[/\\\\]");

    @Tool(name =  ToolFun.TOOL_Glob,
          display = "查找文件",
          description = "文件模式匹配工具，通过 glob 模式查找文件名。\n"
                  + "【重要】pattern 只能写相对路径的模式，例如 \"**/*.java\"、\"src/**/*.xml\"。\n"
                  + "请不要在 pattern 内写盘符或绝对路径（如 D:/xxx），文件夹请用 path 参数指定！\n"
                  + "示例正确用法：\n"
                  + "  - 在 D:/project 下找所有 .mdx 文件: pattern=\"**/*.mdx\", path=\"D:/project\"\n"
                  + "  - 在当前工作目录下找所有 .java: pattern=\"**/*.java\"（不提供 path 即可）\n"
                  + "返回匹配的文件路径（最多 " + DEFAULT_MAX_RESULTS + " 个），按修改时间倒序排列。"
    )
    public String glob(
            @ToolParam(description = "glob 模式（必填），例如 \"**/*.java\"。注意：只能写相对路径模式，不要包含盘符或根路径。",
                       required = true) String pattern,
            @ToolParam(description = "从哪个目录开始搜索（绝对路径，可选）。不填则默认使用当前工作目录。",
                       required = false) String path,
            AgentContextHolder contextHolder) {

        try {
            // ---------- 智能容错：自动从 pattern 中分离绝对路径 ----------
            String actualPattern = pattern;
            String actualPath = path;

            // 如果 pattern 开头看起来像绝对路径（Windows 盘符 或 Unix / 开头）
            if (StringUtils.isBlank(actualPath)) {
                if (WIN_ABSOLUTE_PREFIX.matcher(actualPattern).lookingAt() || actualPattern.startsWith("/")) {
                    // 尝试将 pattern 拆分为 [目录] + [glob 表达式]
                    int lastSep = Math.max(actualPattern.lastIndexOf('/'), actualPattern.lastIndexOf('\\'));
                    if (lastSep > 0) {
                        String possibleDir = actualPattern.substring(0, lastSep);
                        String possibleGlob = actualPattern.substring(lastSep + 1);
                        // 如果剩下的部分看起来像 glob（包含通配符 * ? [ ] 等），则采信
                        if (possibleGlob.contains("*") || possibleGlob.contains("?") || possibleGlob.contains("[")) {
                            actualPath = possibleDir;
                            actualPattern = possibleGlob;
                            log.debug("GlobTool 智能分离: path={}, pattern={}", actualPath, actualPattern);
                        }
                    }
                }
            }

            // 如果分离后仍然没有 path，使用当前工作目录
            if (StringUtils.isBlank(actualPath)) {
                String searchPath = null;
                if (contextHolder != null && StringUtils.isNotBlank(contextHolder.getWorkspaceId())) {
                    try (Workspace wk = Workspace.load(contextHolder.getWorkspaceId())) {
                        searchPath = wk.getStorageRoot().toString();
                    } catch (Exception ignored) {
                    }
                }
                if (StringUtils.isBlank(searchPath)) {
                    return "错误：当前未设置默认工作空间，必须指定 path 参数，或向用户询问查找的根目录路径参数。";
                }
                actualPath = searchPath;
            }

            final Path rootPath = Paths.get(actualPath).toAbsolutePath().normalize();
            if (!Files.exists(rootPath)) {
                return "错误：搜索目录不存在 -> " + rootPath + "\n请检查 path 参数或确认文件夹未被删除/移动。";
            }

            // 压缩 path 中的重复斜杠，避免匹配失败
            final String patternStr = actualPattern.replace('\\', '/');

            // ---------- 构造 matcher 列表（关键修复） ----------
            // JDK 的 PathMatcher 在 "glob:**/xxx" 时会要求至少一个 "/"，导致根目录直挂文件无法命中。
            // 因此除原始 matcher 外，再追加一个去掉 "**/" 前缀的备用 matcher。
            final List<PathMatcher> matchers = new ArrayList<>();
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + patternStr));

            String stripped = patternStr;
            while (stripped.startsWith("**/")) {
                stripped = stripped.substring(3);
            }
            if (!stripped.isEmpty() && !stripped.equals(patternStr)) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + stripped));
            }

            final List<FileEntry> entries = new ArrayList<>();
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(rootPath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String dirName = dir.getFileName().toString();
                    if (EXCLUDED_DIRS.contains(dirName) || dirName.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Path relative = rootPath.relativize(file);
                        // 统一使用 '/' 来避免 Windows 上的匹配差异
                        String relativeStr = relative.toString().replace('\\', '/');
                        Path fileName = file.getFileName();

                        for (PathMatcher m : matchers) {
                            if (m.matches(fileName) || m.matches(Paths.get(relativeStr))) {
                                entries.add(new FileEntry(relativeStr, attrs.lastModifiedTime().toMillis()));
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            // 按修改时间倒序
            entries.sort((a, b) -> Long.compare(b.modifiedTime, a.modifiedTime));

            int total = entries.size();
            boolean truncated = total > DEFAULT_MAX_RESULTS;
            List<FileEntry> shown = entries.subList(0, Math.min(total, DEFAULT_MAX_RESULTS));

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("在 \"%s\" 下匹配 glob \"%s\" 的结果:", actualPath, patternStr));
            sb.append(String.format(" 共 %d 个文件", total));
            if (truncated) {
                sb.append(String.format("，仅显示前 %d 个", DEFAULT_MAX_RESULTS));
            }
            sb.append("\n");

            if (total == 0) {
                sb.append("（无匹配文件）\n");
                sb.append("建议：1. 确认搜索目录是否正确；2. 模式不要写绝对路径；3. 检查文件是否在排除目录中（如 .git, node_modules 等）。\n");
            } else {
                for (FileEntry e : shown) {
                    sb.append(e.path).append("\n");
                }
                if (truncated) {
                    sb.append(String.format("\n[结果已截断: 显示 %d/%d 个文件]", DEFAULT_MAX_RESULTS, total));
                }
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("GlobTool 执行失败", e);
            return "文件匹配发生异常: " + e.getMessage();
        }
    }

    private static class FileEntry {
        final String path;
        final long modifiedTime;

        FileEntry(String path, long modifiedTime) {
            this.path = path;
            this.modifiedTime = modifiedTime;
        }
    }
}
