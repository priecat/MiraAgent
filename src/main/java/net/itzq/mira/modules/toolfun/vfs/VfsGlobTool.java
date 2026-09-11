package net.itzq.mira.modules.toolfun.vfs;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.tool.annotation.Tool;
import net.itzq.mira.modules.ai.tool.annotation.ToolParam;
import net.itzq.mira.modules.toolfun.ToolFun;
import net.itzq.mira.modules.vfs.VFS;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * GlobTool - 内存 Var_VFS 文件模式匹配工具
 *
 * - 自动识别 pattern 中的绝对路径并分离为 path 参数
 * - 空结果时给出排查建议
 */
@Slf4j
public class VfsGlobTool {

    private static final int DEFAULT_MAX_RESULTS = 100;

    /** 自动排除的目录 */
    private static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
            ".git", ".svn", ".hg", ".bzr", ".jj", ".sl", ".idea", ".vscode",
            "node_modules", "__pycache__", ".mvn", "target", "build", "dist", ".cache"
    ));

    // 匹配 Windows 绝对路径前缀（如 "D:/" 或 "D:\"）
    private static final java.util.regex.Pattern WIN_ABSOLUTE_PREFIX =
            java.util.regex.Pattern.compile("^[a-zA-Z]:[/\\\\]");

    @Tool(name = ToolFun.Tool_VFS_Glob,
          display = "查找文件",
          description = "内存文件系统文件模式匹配工具，通过 glob 模式查找文件名。\n"
                  + "【重要】pattern 只能写相对路径的模式，例如 \"**/*.java\"、\"src/**/*.xml\"。\n"
                  + "请不要在 pattern 内写盘符或绝对路径（如 D:/xxx），文件夹请用 path 参数指定！\n"
                  + "示例正确用法：\n"
                  + "  - 在 Var_VFS 根目录下找所有 .mdx 文件: pattern=\"**/*.mdx\"（不提供 path，默认搜索 /）\n"
                  + "  - 在 /src 下找所有 .java: pattern=\"**/*.java\", path=\"/src\"\n"
                  + "返回匹配的文件路径（最多 " + DEFAULT_MAX_RESULTS + " 个），按修改时间倒序排列。"
           )
    public String glob(
            @ToolParam(description = "glob 模式（必填），例如 \"**/*.java\"。注意：只能写相对路径模式，不要包含盘符或根路径。",
                       required = true) String pattern,
            @ToolParam(description = "从哪个目录开始搜索（Var_VFS 路径，可选）。不填则默认搜索 Var_VFS 根目录 '/'。",
                       required = false) String path,
            AgentContextHolder contextHolder) {

        if (StringUtils.isBlank(contextHolder.getVfsId())){
            return "错误: 虚拟文件系统未初始化";
        }

        try (VFS vfs = VFS.load(contextHolder.getVfsId())) {
            FileSystem fs = vfs.getFileSystemForRead();
            if (fs == null) {
                return "虚拟文件系统为空，尚无文件。建议先使用写入工具上传或创建文件后再搜索。";
            }

            // ---------- 智能容错：自动从 pattern 中分离绝对路径 ----------
            String actualPattern = pattern;
            String actualPath = path;

            if (actualPath == null || StringUtils.isBlank(actualPath)) {
                if (WIN_ABSOLUTE_PREFIX.matcher(actualPattern).lookingAt() || actualPattern.startsWith("/")) {
                    int lastSep = Math.max(actualPattern.lastIndexOf('/'), actualPattern.lastIndexOf('\\'));
                    if (lastSep > 0) {
                        String possibleDir = actualPattern.substring(0, lastSep);
                        String possibleGlob = actualPattern.substring(lastSep + 1);
                        if (possibleGlob.contains("*") || possibleGlob.contains("?") || possibleGlob.contains("[")) {
                            actualPath = possibleDir;
                            actualPattern = possibleGlob;
                            log.debug("GlobTool 智能分离: path={}, pattern={}", actualPath, actualPattern);
                        }
                    }
                }
            }
            // 如果分离后仍无 path，默认使用 Var_VFS 根路径
            if (actualPath == null || StringUtils.isBlank(actualPath)) {
                actualPath = "/";
            }

            Path rootPath = fs.getPath(actualPath);
            if (!Files.exists(rootPath)) {
                return "错误：搜索目录不存在 -> " + rootPath + "\n请检查 path 参数或确认文件夹未被删除/移动。";
            }

            // 压缩 pattern 中的重复斜杠
            final String patternStr = actualPattern.replace('\\', '/');

            // ---------- 构造 matcher 列表 ----------
            // JDK 的 PathMatcher 在 "glob:**/xxx" 时要求至少一个 "/"，导致根目录直挂文件无法命中。
            // 因此除原始 matcher 外，再追加一个去掉 "**/" 前缀的备用 matcher。
            final List<PathMatcher> matchers = new ArrayList<>();
            matchers.add(fs.getPathMatcher("glob:" + patternStr));
            String stripped = patternStr;
            while (stripped.startsWith("**/")) {
                stripped = stripped.substring(3);
            }
            if (!stripped.isEmpty() && !stripped.equals(patternStr)) {
                matchers.add(fs.getPathMatcher("glob:" + stripped));
            }

            List<FileEntry> entries = new ArrayList<>();
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // 根目录 getFileName() 为 null，需跳过
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
                            // 必须使用同一 FileSystem 的 Path，否则抛 ProviderMismatchException
                            if (m.matches(fileName) || m.matches(fs.getPath(relativeStr))) {
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
