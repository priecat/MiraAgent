package net.itzq.mira.modules.toolfun.code;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.tool.annotation.Tool;
import net.itzq.mira.modules.ai.tool.annotation.ToolParam;
import net.itzq.mira.modules.ai.tool.ToolCallResult;
import net.itzq.mira.modules.toolfun.ToolFun;
import net.itzq.mira.core.utils.StringUtils;

import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static net.itzq.mira.modules.toolfun.code.FileReadTool.BLOCKED_PATHS;

/**
 * GlobTool - 文件模式匹配工具
 *
 * - glob 模式查找文件名，结果按修改时间倒序
 * - 容错：path 未显式提供时，若 pattern 里混入了绝对路径（如 "D:/proj/**&#47;*.mdx"），
 *   自动把「目录部分」与「glob 部分」拆开（注意：只在 path 未提供时生效）
 * - 自动跳过隐藏目录与常见构建/依赖目录
 * - 非法 glob 模式给出友好错误，而不是抛异常
 *
 * @author tangzq
 */
@Slf4j
public class GlobTool {

    private static final int DEFAULT_MAX_RESULTS = 100;

    /** 自动排除的目录 */
    private static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
            ".git", ".svn", ".hg", ".bzr", ".jj", ".sl", ".idea", ".vscode",
            "node_modules", "__pycache__", ".mvn", "target", "build", "dist", ".cache"
    ));

    /** 匹配 Windows 绝对路径前缀（如 "D:/" 或 "D:\"） */
    private static final Pattern WIN_ABSOLUTE_PREFIX = Pattern.compile("^[a-zA-Z]:[/\\\\]");

    @Tool(name = ToolFun.TOOL_Glob,
          display = "查找文件",
          description = "文件模式匹配工具，通过 glob 模式查找文件名。\n"
                  + "【重要】pattern 只写相对路径的模式，例如 \"**/*.java\"、\"src/**/*.xml\"。\n"
                  + "不要把盘符或绝对路径写进 pattern（如 D:/xxx），文件夹请用 path 参数指定！\n"
                  + "示例正确用法：\n"
                  + "  - 在 D:/project 下找所有 .mdx 文件: pattern=\"**/*.mdx\", path=\"D:/project\"\n"
                  + "  - 在当前工作目录下找所有 .java: pattern=\"**/*.java\"（不提供 path 即可）\n"
                  + "返回匹配的文件路径（最多 " + DEFAULT_MAX_RESULTS + " 个），按修改时间倒序排列。"
    )
    public String glob(
            @ToolParam(description = "glob 模式（必填），例如 \"**/*.java\"。只能写相对路径模式，不要包含盘符或根路径。",
                       required = true) String pattern,
            @ToolParam(description = "从哪个目录开始搜索（绝对路径），留空默认使用当前工作空间目录",
                       required = false) String path,
            AgentContextHolder contextHolder) {

        try {
            if (StringUtils.isBlank(pattern)) {
                return ToolCallResult.error("错误：pattern 不能为空。示例：pattern=\"**/*.java\"");
            }

            boolean pathProvided = StringUtils.isNotBlank(path);
            String actualPattern = pattern.trim();
            String actualPath = pathProvided ? path.trim() : null;

            // 容错：仅当 path 未显式提供时，才尝试从 pattern 中分离绝对路径前缀
            if (!pathProvided) {
                String[] split = splitAbsolutePrefix(actualPattern);
                if (split != null) {
                    actualPath = split[0];
                    actualPattern = split[1];
                    log.debug("GlobTool 智能分离: path={}, pattern={}", actualPath, actualPattern);
                }
            }

            if (StringUtils.isBlank(actualPath)) {
                actualPath = contextHolder.getWorkspacePath();
            }
            if (StringUtils.isBlank(actualPath)) {
                return ToolCallResult.error("错误：当前未设置默认工作空间，必须指定 path 参数，或向用户询问查找的根目录路径参数。");
            }

            Path rootPath = Paths.get(actualPath).toAbsolutePath().normalize();

            // 安全检查
            String pathStr = rootPath.toString();
            for (String blocked : BLOCKED_PATHS) {
                if (pathStr.contains(blocked) || pathStr.equals(blocked)) {
                    return ToolCallResult.error("安全限制: 无法读取设备文件或特殊文件: " + actualPath);
                }
            }

            if (!Files.exists(rootPath)) {
                return ToolCallResult.error("错误：搜索目录不存在 -> " + rootPath + "\n请检查 path 参数或确认文件夹未被删除/移动。");
            }
            if (!Files.isDirectory(rootPath)) {
                return ToolCallResult.error("错误：path 不是目录 -> " + rootPath);
            }

            // 统一使用 '/' 避免 Windows 上的匹配差异
            final String patternStr = actualPattern.replace('\\', '/');

            // 构造 matcher 列表：
            // JDK 的 PathMatcher 在 "glob:**/xxx" 时会要求至少一个 "/"，导致根目录直挂文件无法命中，
            // 因此除原始 matcher 外，再追加一个去掉 "**/" 前缀的备用 matcher。
            final List<PathMatcher> matchers = new ArrayList<>();
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + patternStr));
            } catch (IllegalArgumentException e) {
                return ToolCallResult.error(String.format("错误：无效的 glob 模式 \"%s\" —— %s\n示例：\"**/*.java\"、\"src/**/*.xml\"",
                        patternStr, e.getMessage()));
            }
            String stripped = patternStr;
            while (stripped.startsWith("**/")) {
                stripped = stripped.substring(3);
            }
            if (!stripped.isEmpty() && !stripped.equals(patternStr)) {
                try {
                    matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + stripped));
                } catch (IllegalArgumentException ignored) {
                    // 备用 matcher 构造失败不影响主 matcher
                }
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
                        String relativeStr = relative.toString().replace('\\', '/');
                        Path fileName = file.getFileName();

                        for (PathMatcher m : matchers) {
                            if (m.matches(fileName) || m.matches(Paths.get(relativeStr))) {
                                entries.add(new FileEntry(relativeStr, attrs.lastModifiedTime().toMillis()));
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                        // 单个文件匹配失败不应中断整个遍历
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
            sb.append(String.format("在 \"%s\" 下匹配 glob \"%s\" 的结果:", rootPath, patternStr));
            sb.append(String.format(" 共 %d 个文件", total));
            if (truncated) {
                sb.append(String.format("，仅显示前 %d 个", DEFAULT_MAX_RESULTS));
            }
            sb.append("\n");

            if (total == 0) {
                sb.append("（无匹配文件）\n");
                sb.append("建议：1. 确认搜索目录是否正确；2. 模式不要写绝对路径；3. 检查文件是否在排除目录中（如 .git, node_modules, target 等）。\n");
            } else {
                for (FileEntry e : shown) {
                    sb.append(e.path).append("\n");
                }
                if (truncated) {
                    sb.append(String.format("\n[结果已截断: 显示 %d/%d 个文件]", DEFAULT_MAX_RESULTS, total));
                }
            }

            return ToolCallResult.successUnlessMarked(sb.toString());

        } catch (Exception e) {
            log.error("GlobTool 执行失败", e);
            return ToolCallResult.error("文件匹配发生异常: " + e.getMessage());
        }
    }

    /**
     * 若 pattern 以绝对路径开头，则按「第一个含通配符的路径段」切分：
     * "D:/proj/**&#47;*.mdx" → ["D:/proj", "**&#47;*.mdx"]。
     *
     * @return [目录, glob]；不是绝对路径或不含通配符时返回 null
     */
    private String[] splitAbsolutePrefix(String pattern) {
        String p = pattern.replace('\\', '/');
        boolean absolute = WIN_ABSOLUTE_PREFIX.matcher(p).lookingAt() || p.startsWith("/");
        if (!absolute) {
            return null;
        }
        int wildcardIndex = -1;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '*' || c == '?' || c == '[') {
                wildcardIndex = i;
                break;
            }
        }
        if (wildcardIndex < 0) {
            return null;
        }
        int slash = p.lastIndexOf('/', wildcardIndex);
        if (slash <= 0) {
            return null;
        }
        String dir = p.substring(0, slash);
        String glob = p.substring(slash + 1);
        if (dir.isEmpty() || glob.isEmpty()) {
            return null;
        }
        return new String[]{dir, glob};
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
