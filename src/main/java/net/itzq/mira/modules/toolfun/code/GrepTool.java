package net.itzq.mira.modules.toolfun.code;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.tool.annotation.Tool;
import net.itzq.mira.modules.ai.tool.annotation.ToolParam;
import net.itzq.mira.modules.ai.tool.ToolCallResult;
import net.itzq.mira.modules.toolfun.ToolFun;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static net.itzq.mira.modules.toolfun.code.FileReadTool.BLOCKED_PATHS;

/**
 * GrepTool - 基于 Java NIO 的内容搜索工具
 *
 * - 支持完整正则表达式语法（Java 引擎）
 * - 支持 glob 文件过滤与 type 类型过滤
 * - 输出模式：content / files_with_matches / count
 * - 真实上下文行（-B/-A/-C）：真的回读并输出匹配行前后的内容，命中不连续处用 `--` 分隔
 * - 真实多行匹配：multiline=true 时按「整个文件」做 DOTALL 匹配，可跨行命中
 * - 编码与二进制安全：UTF-8 / UTF-16 / GBK 探测解码，含 NUL 或不可打印比例过高的文件跳过
 * - 自动排除 VCS / 构建目录，跳过超大文件
 *
 * @author tangzq
 */
@Slf4j
public class GrepTool {

    private static final int DEFAULT_HEAD_LIMIT = 250;
    /** 单个文件超过该大小跳过（避免把巨物读进内存） */
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    /** 用于渲染上下文而保留的文件内容总预算 */
    private static final long RETAIN_BUDGET_BYTES = 32L * 1024 * 1024;
    /** 单行展示的最大长度 */
    private static final int MAX_LINE_DISPLAY = 500;
    /** 命中文件数上限（防止超宽搜索结果撑爆内存） */
    private static final int MAX_HIT_FILES = 20000;
    private static final int BINARY_SAMPLE_BYTES = 4096;

    /** 自动排除的 VCS 和工具目录 */
    private static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
            ".git", ".svn", ".hg", ".bzr", ".jj", ".sl", ".idea", ".vscode",
            "node_modules", "__pycache__", ".mvn", "target", "build", "dist", ".cache"
    ));

    /** 文件类型到扩展名的映射 */
    private static final Map<String, List<String>> TYPE_EXTENSIONS = new HashMap<>();

    static {
        TYPE_EXTENSIONS.put("java", Arrays.asList(".java"));
        TYPE_EXTENSIONS.put("py", Arrays.asList(".py", ".pyx", ".pyi"));
        TYPE_EXTENSIONS.put("js", Arrays.asList(".js", ".jsx", ".mjs", ".cjs"));
        TYPE_EXTENSIONS.put("ts", Arrays.asList(".ts", ".tsx"));
        TYPE_EXTENSIONS.put("go", Arrays.asList(".go"));
        TYPE_EXTENSIONS.put("rust", Arrays.asList(".rs"));
        TYPE_EXTENSIONS.put("cpp", Arrays.asList(".cpp", ".cc", ".cxx", ".hpp", ".h", ".hxx"));
        TYPE_EXTENSIONS.put("c", Arrays.asList(".c", ".h", ".m"));
        TYPE_EXTENSIONS.put("xml", Arrays.asList(".xml", ".xsd", ".xsl"));
        TYPE_EXTENSIONS.put("json", Arrays.asList(".json"));
        TYPE_EXTENSIONS.put("yaml", Arrays.asList(".yml", ".yaml"));
        TYPE_EXTENSIONS.put("yml", Arrays.asList(".yml", ".yaml"));
        TYPE_EXTENSIONS.put("md", Arrays.asList(".md", ".mdx", ".markdown"));
        TYPE_EXTENSIONS.put("html", Arrays.asList(".html", ".htm", ".xhtml"));
        TYPE_EXTENSIONS.put("css", Arrays.asList(".css", ".scss", ".sass", ".less"));
        TYPE_EXTENSIONS.put("sql", Arrays.asList(".sql"));
        TYPE_EXTENSIONS.put("sh", Arrays.asList(".sh", ".bash", ".zsh"));
        TYPE_EXTENSIONS.put("txt", Arrays.asList(".txt", ".text", ".log"));
        TYPE_EXTENSIONS.put("properties", Arrays.asList(".properties"));
    }

    @Tool(name = ToolFun.TOOL_Grep,
          display = "内容搜索",
          description = "基于正则表达式的强大内容搜索工具。\n\n"
                    + "使用说明：\n"
                    + "- 始终使用 Grep 进行内容搜索，不要通过 Bash 工具调用 grep/rg 命令\n"
                    + "- 支持完整正则表达式语法（例如 \"log.*Error\"、\"function\\s+\\w+\"）\n"
                    + "- 使用 glob 参数过滤文件（例如 \"*.js\"、\"**/*.tsx\"，多个后缀可用 \"*.{java,xml}\"）或 type 参数过滤文件类型\n"
                    + "- 输出模式：\"content\" 显示匹配行内容、\"files_with_matches\" 仅显示文件路径（默认）、\"count\" 显示匹配数量\n"
                    + "- 上下文行：context_before / context_after / context_around 会返回真实的上下文内容\n"
                    + "- 多行匹配：默认单行匹配；跨行模式请设置 multiline=true（此时按整个文件匹配）\n"
                    + "- 对于需要多轮搜索的开放式任务，使用 SubAgent 工具代替\n"
                    + "- 正则语法：使用 Java 正则引擎，特殊字符需要转义（如 `interface\\{\\}` 匹配 Go 代码中的 `interface{}`）"
    )
    public String grep(
            @ToolParam(description = "正则表达式搜索模式（必填）") String pattern,
            @ToolParam(description = "搜索目录路径，留空默认使用当前工作空间目录", required = false) String path,
            @ToolParam(description = "文件过滤 glob 模式，例如 \"*.java\"、\"**/*.xml\"、\"*.{java,xml}\"", required = false) String glob,
            @ToolParam(description = "输出模式: content(显示匹配行), files_with_matches(仅文件路径, 默认), count(匹配数量)", required = false) String outputMode,
            @ToolParam(description = "显示匹配行前 N 行上下文", required = false) Integer contextBefore,
            @ToolParam(description = "显示匹配行后 N 行上下文", required = false) Integer contextAfter,
            @ToolParam(description = "上下文行数（等价于同时设置前后各 N 行）", required = false) Integer contextAround,
            @ToolParam(description = "显示行号，默认 true", required = false) Boolean showLineNumber,
            @ToolParam(description = "大小写不敏感匹配", required = false) Boolean caseInsensitive,
            @ToolParam(description = "文件类型过滤，如 \"java\"、\"py\"、\"js\"、\"xml\" 等", required = false) String type,
            @ToolParam(description = "限制输出行数，默认 250，0 表示不限制", required = false) Integer headLimit,
            @ToolParam(description = "跳过前 N 条匹配结果（用于分页）", required = false) Integer offset,
            @ToolParam(description = "启用跨行匹配模式（整文件 DOTALL 匹配）", required = false) Boolean multiline,
            AgentContextHolder contextHolder) {

        try {
            String searchPath = path;
            if (StringUtils.isBlank(searchPath)) {
                searchPath = contextHolder.getWorkspacePath();
            }
            if (StringUtils.isBlank(searchPath)) {
                return ToolCallResult.error("错误：当前未设置默认工作空间，必须指定 path 参数，或向用户询问查找的根目录路径参数。");
            }

            Path rootPath = Paths.get(searchPath).toAbsolutePath().normalize();
            String pathStr = rootPath.toString();
            for (String blocked : BLOCKED_PATHS) {
                if (pathStr.contains(blocked) || pathStr.equals(blocked)) {
                    return ToolCallResult.error("安全限制: 无法读取设备文件或特殊文件: " + searchPath);
                }
            }
            if (!Files.exists(rootPath)) {
                return ToolCallResult.error("搜索目录不存在: " + searchPath);
            }
            if (!Files.isDirectory(rootPath)) {
                return ToolCallResult.error("错误：path 不是目录 -> " + rootPath);
            }

            String mode = StringUtils.isBlank(outputMode) ? "files_with_matches" : outputMode.trim();
            boolean contentMode = "content".equals(mode);
            int ctxBefore = contextBefore != null ? Math.max(0, contextBefore) : 0;
            int ctxAfter = contextAfter != null ? Math.max(0, contextAfter) : 0;
            if (contextAround != null && contextAround > 0) {
                ctxBefore = contextAround;
                ctxAfter = contextAround;
            }
            if (!contentMode) {
                ctxBefore = 0;
                ctxAfter = 0;
            }
            boolean showNum = showLineNumber == null || showLineNumber;
            int limit = headLimit != null ? headLimit : DEFAULT_HEAD_LIMIT;
            if (limit <= 0) {
                limit = Integer.MAX_VALUE;
            }
            int skip = offset != null ? Math.max(0, offset) : 0;
            boolean multi = multiline != null && multiline;

            // 编译正则
            int flags = 0;
            if (caseInsensitive != null && caseInsensitive) {
                flags |= Pattern.CASE_INSENSITIVE;
            }
            if (multi) {
                flags |= Pattern.DOTALL | Pattern.MULTILINE;
            }
            final Pattern regex;
            try {
                regex = Pattern.compile(pattern, flags);
            } catch (PatternSyntaxException e) {
                return ToolCallResult.error("正则表达式语法错误: " + e.getMessage());
            }

            // 文件过滤 matcher
            final List<PathMatcher> globMatchers = new ArrayList<>();
            if (StringUtils.isNotBlank(glob)) {
                String g = glob.trim().replace('\\', '/');
                try {
                    globMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
                } catch (IllegalArgumentException e) {
                    return ToolCallResult.error("glob 模式无效: " + g + " —— " + e.getMessage());
                }
                String stripped = g;
                while (stripped.startsWith("**/")) {
                    stripped = stripped.substring(3);
                }
                if (!stripped.isEmpty() && !stripped.equals(g)) {
                    try {
                        globMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + stripped));
                    } catch (IllegalArgumentException ignored) {
                        // 备用 matcher 失败不影响主 matcher
                    }
                }
            }

            final List<String> typeExts = StringUtils.isNotBlank(type)
                    ? TYPE_EXTENSIONS.get(type.trim().toLowerCase())
                    : null;
            if (StringUtils.isNotBlank(type) && typeExts == null) {
                return ToolCallResult.error("不支持的 type: " + type + "（可用如 java / py / js / ts / xml / json / md 等）");
            }

            final List<FileHit> hits = new ArrayList<>();
            final boolean keepLines = contentMode;
            final long[] retainedBytes = {0L};

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
                    if (hits.size() >= MAX_HIT_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!passesType(typeExts, file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!globMatchers.isEmpty() && !passesGlob(globMatchers, rootPath, file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attrs.size() > MAX_FILE_BYTES) {
                        return FileVisitResult.CONTINUE;
                    }

                    byte[] bytes;
                    try {
                        bytes = Files.readAllBytes(file);
                    } catch (IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                    // 二进制跳过（UTF-16 带 BOM 的正文含 NUL，需先排除）
                    if (!TextFileCodec.hasUtf16Bom(bytes)
                            && TextFileCodec.isBinary(TextFileCodec.sample(bytes, BINARY_SAMPLE_BYTES))) {
                        return FileVisitResult.CONTINUE;
                    }
                    TextFileCodec.Decoded decoded = TextFileCodec.decode(bytes);
                    if (decoded == null) {
                        return FileVisitResult.CONTINUE;
                    }

                    String content = TextFileCodec.normalizeLineEndings(decoded.text);
                    String[] lines = splitLines(content);

                    TreeSet<Integer> matched = new TreeSet<>();
                    int count = 0;
                    if (multi) {
                        Matcher m = regex.matcher(content);
                        while (m.find()) {
                            count++;
                            int startLine = lineOfOffset(content, m.start());
                            int endLine = m.end() > m.start()
                                    ? lineOfOffset(content, m.end() - 1)
                                    : startLine;
                            for (int ln = startLine; ln <= endLine && ln <= lines.length; ln++) {
                                matched.add(ln);
                            }
                            if (m.end() == m.start()) {
                                break; // 空匹配防死循环
                            }
                        }
                    } else {
                        for (int i = 0; i < lines.length; i++) {
                            if (regex.matcher(lines[i]).find()) {
                                matched.add(i + 1);
                                count++;
                            }
                        }
                    }
                    if (count == 0) {
                        return FileVisitResult.CONTINUE;
                    }

                    FileHit hit = new FileHit(relativePath(rootPath, file), matched, count);
                    if (keepLines && retainedBytes[0] + bytes.length <= RETAIN_BUDGET_BYTES) {
                        hit.lines = lines;
                        retainedBytes[0] += bytes.length;
                    }
                    hits.add(hit);
                    return FileVisitResult.CONTINUE;
                }
            });

            int totalMatches = 0;
            for (FileHit hit : hits) {
                totalMatches += hit.matchCount;
            }

            if ("files_with_matches".equals(mode)) {
                return ToolCallResult.successUnlessMarked(renderFiles(hits, limit, skip, totalMatches, rootPath));
            }
            if ("count".equals(mode)) {
                return ToolCallResult.successUnlessMarked(renderCount(hits));
            }
            return ToolCallResult.successUnlessMarked(renderContent(hits, limit, skip, totalMatches, ctxBefore, ctxAfter, showNum));

        } catch (Exception e) {
            log.error("GrepTool 执行失败", e);
            return ToolCallResult.error("搜索执行失败: " + e.getMessage());
        }
    }

    // ---- 过滤辅助 ----

    private boolean passesType(List<String> typeExts, Path file) {
        if (typeExts == null) {
            return true;
        }
        String name = file.getFileName().toString().toLowerCase();
        for (String ext : typeExts) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private boolean passesGlob(List<PathMatcher> matchers, Path rootPath, Path file) {
        Path fileName = file.getFileName();
        String relativeStr = rootPath.relativize(file).toString().replace('\\', '/');
        for (PathMatcher m : matchers) {
            try {
                if (m.matches(fileName) || m.matches(Paths.get(relativeStr))) {
                    return true;
                }
            } catch (Exception ignored) {
                // 个别平台差异导致的匹配异常不影响其它 matcher
            }
        }
        return false;
    }

    private String relativePath(Path rootPath, Path file) {
        try {
            return rootPath.relativize(file).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.toString();
        }
    }

    // ---- 行/偏移工具 ----

    private String[] splitLines(String content) {
        String[] parts = content.split("\n", -1);
        if (parts.length > 0 && parts[parts.length - 1].isEmpty()) {
            return Arrays.copyOf(parts, parts.length - 1);
        }
        return parts;
    }

    /** 偏移量（0-based）所在的行号（1-based） */
    private int lineOfOffset(String content, int offset) {
        int line = 1;
        int max = Math.min(offset, content.length());
        for (int i = 0; i < max; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private String clip(String line) {
        return line.length() > MAX_LINE_DISPLAY
                ? line.substring(0, MAX_LINE_DISPLAY) + "..."
                : line;
    }

    // ---- 输出渲染 ----

    /** files_with_matches：仅文件路径（按 skip 跳过前 N 个文件） */
    private String renderFiles(List<FileHit> hits, int limit, int skip, int totalMatches, Path rootPath) {
        int totalFiles = hits.size();
        int from = Math.min(skip, totalFiles);
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (int i = from; i < totalFiles; i++) {
            files.add(hits.get(i).path);
            if (files.size() >= limit) {
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        if (skip > 0 || totalFiles > files.size()) {
            sb.append(String.format("[结果分页: 限制 %d, 偏移 %d, 共 %d 个文件, %d 条匹配]\n",
                    limit == Integer.MAX_VALUE ? totalFiles : limit, skip, totalFiles, totalMatches));
        }
        for (String f : files) {
            sb.append(f).append("\n");
        }
        if (from + files.size() < totalFiles) {
            sb.append(String.format("\n[已截断: 显示 %d/%d 个文件]", files.size(), totalFiles));
        }
        return sb.toString();
    }

    /** count：每个文件的匹配数 */
    private String renderCount(List<FileHit> hits) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (FileHit hit : hits) {
            sb.append(String.format("%d\t%s\n", hit.matchCount, hit.path));
            total += hit.matchCount;
        }
        sb.append(String.format("\n总计: %d 条匹配分布在 %d 个文件中\n", total, hits.size()));
        return sb.toString();
    }

    /** content：真实上下文行 + 分组（命中不连续处用 `--` 分隔） */
    private String renderContent(List<FileHit> hits, int limit, int skip, int totalMatches,
            int ctxBefore, int ctxAfter, boolean showNum) {
        StringBuilder sb = new StringBuilder();
        if (skip > 0 || totalMatches > limit) {
            sb.append(String.format("[结果分页: 限制 %d, 偏移 %d, 共 %d 条匹配]\n",
                    limit == Integer.MAX_VALUE ? totalMatches : limit, skip, totalMatches));
        }

        int printedMatches = 0;
        int skipped = 0;

        for (FileHit hit : hits) {
            if (printedMatches >= limit) {
                break;
            }
            // 未保留内容（超出预算）：退化为「文件:行号」
            if (hit.lines == null) {
                for (Integer ln : hit.matchedLines) {
                    if (skipped < skip) {
                        skipped++;
                        continue;
                    }
                    if (printedMatches >= limit) {
                        break;
                    }
                    sb.append(String.format("%s:%d\n", hit.path, ln));
                    printedMatches++;
                }
                continue;
            }

            boolean headerPrinted = false;
            int lastPrintedLine = -2;
            for (Integer ln : hit.matchedLines) {
                if (skipped < skip) {
                    skipped++;
                    continue;
                }
                if (printedMatches >= limit) {
                    break;
                }
                int from = Math.max(1, ln - ctxBefore);
                int to = Math.min(hit.lines.length, ln + ctxAfter);

                if (!headerPrinted) {
                    sb.append(hit.path).append(":\n");
                    headerPrinted = true;
                } else if (from > lastPrintedLine + 1) {
                    sb.append("--\n");
                }

                for (int i = from; i <= to; i++) {
                    if (i <= lastPrintedLine) {
                        continue;
                    }
                    boolean isMatch = hit.matchedLines.contains(i);
                    String text = clip(hit.lines[i - 1]);
                    if (showNum) {
                        sb.append(String.format("%6d%s %s\n", i, isMatch ? ":" : "-", text));
                    } else {
                        sb.append(text).append("\n");
                    }
                    lastPrintedLine = i;
                }
                printedMatches++;
            }
        }

        if (printedMatches < totalMatches) {
            sb.append(String.format("\n[已截断: 显示 %d/%d 条匹配]", printedMatches, totalMatches));
        }
        if (sb.length() == 0) {
            return "（无匹配内容）";
        }
        return sb.toString();
    }

    /** 单个文件的命中信息 */
    private static final class FileHit {
        final String path;
        final TreeSet<Integer> matchedLines;
        final int matchCount;
        /** 仅在 content 模式且保留预算允许时非空，用于渲染上下文 */
        String[] lines;

        FileHit(String path, TreeSet<Integer> matchedLines, int matchCount) {
            this.path = path;
            this.matchedLines = matchedLines;
            this.matchCount = matchCount;
        }
    }
}
