package net.itzq.mira.modules.toolfun.code;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.tool.annotation.Tool;
import net.itzq.mira.modules.ai.tool.annotation.ToolParam;
import net.itzq.mira.modules.toolfun.ToolFun;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static net.itzq.mira.modules.toolfun.code.FileReadTool.BLOCKED_PATHS;

/**
 * GrepTool - 基于 Java NIO 的内容搜索工具
 *
 * - 支持完整正则表达式语法
 * - 支持 glob 文件过滤和 type 过滤
 * - 支持 content/files_with_matches/count 三种输出模式
 * - 支持上下文行（-B/-A/-C）
 * - 自动排除 VCS 目录（.git, .svn 等）
 * - 默认 head_limit 250 行
 *
 * @author tangzq
 */
@Slf4j
public class GrepTool {

    private static final int DEFAULT_HEAD_LIMIT = 250;

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
        TYPE_EXTENSIONS.put("md", Arrays.asList(".md", ".mdx", ".markdown"));
        TYPE_EXTENSIONS.put("html", Arrays.asList(".html", ".htm", ".xhtml"));
        TYPE_EXTENSIONS.put("css", Arrays.asList(".css", ".scss", ".sass", ".less"));
        TYPE_EXTENSIONS.put("sql", Arrays.asList(".sql"));
        TYPE_EXTENSIONS.put("sh", Arrays.asList(".sh", ".bash", ".zsh"));
        TYPE_EXTENSIONS.put("txt", Arrays.asList(".txt", ".text", ".log"));
        TYPE_EXTENSIONS.put("properties", Arrays.asList(".properties"));
        TYPE_EXTENSIONS.put("yml", Arrays.asList(".yml", ".yaml"));
    }

    @Tool(name =  ToolFun.TOOL_Grep,
          display = "内容搜索",
          description = "基于正则表达式的强大内容搜索工具。\n\n"
                    + "使用说明：\n"
                    + "- 始终使用 Grep 进行内容搜索，不要通过 Bash 工具调用 grep/rg 命令\n"
                    + "- 支持完整正则表达式语法（例如 \"log.*Error\"、\"function\\s+\\w+\"）\n"
                    + "- 使用 glob 参数过滤文件（例如 \"*.js\"、\"**/*.tsx\"）或 type 参数过滤文件类型\n"
                    + "- 输出模式：\"content\" 显示匹配行内容、\"files_with_matches\" 仅显示文件路径（默认）、\"count\" 显示匹配数量\n"
                    + "- 对于需要多轮搜索的开放式任务，使用 SubAgent 工具代替\n"
                    + "- 正则语法：使用 Java 正则引擎，特殊字符需要转义（如 `interface\\{\\}` 匹配 Go 代码中的 `interface{}`）\n"
                    + "- 多行匹配：默认单行匹配。跨行模式请设置 multiline=true"
          )
    public String grep(
            @ToolParam(description = "正则表达式搜索模式（必填）") String pattern,
            @ToolParam(description = "搜索目录路径", required = true) String path,
            @ToolParam(description = "文件过滤 glob 模式，例如 \"*.java\"、\"**/*.xml\"", required = false) String glob,
            @ToolParam(description = "输出模式: content(显示匹配行), files_with_matches(仅文件路径, 默认), count(匹配数量)", required = false) String outputMode,
            @ToolParam(description = "显示匹配行前 N 行上下文", required = false) Integer contextBefore,
            @ToolParam(description = "显示匹配行后 N 行上下文", required = false) Integer contextAfter,
            @ToolParam(description = "上下文行数（等价于同时设置前后各 N 行）", required = false) Integer contextAround,
            @ToolParam(description = "显示行号，默认 true", required = false) Boolean showLineNumber,
            @ToolParam(description = "大小写不敏感匹配", required = false) Boolean caseInsensitive,
            @ToolParam(description = "文件类型过滤，如 \"java\"、\"py\"、\"js\"、\"xml\" 等", required = false) String type,
            @ToolParam(description = "限制输出行数，默认 250，0 表示不限制", required = false) Integer headLimit,
            @ToolParam(description = "跳过前 N 条匹配结果（用于分页）", required = false) Integer offset,
            @ToolParam(description = "启用跨行匹配模式（dotall + multiline）", required = false) Boolean multiline,
            AgentContextHolder contextHolder) {

        try {
            String searchPath = path;

            Path path0 = Paths.get(searchPath).toAbsolutePath().normalize();
            // 安全检查
            String pathStr = path0.toString();
            for (String blocked : BLOCKED_PATHS) {
                if (pathStr.contains(blocked) || pathStr.equals(blocked)) {
                    return "安全限制: 无法读取设备文件或特殊文件: " + searchPath;
                }
            }

            String mode = StringUtils.isBlank(outputMode) ? "files_with_matches" : outputMode;
            int ctxBefore = contextBefore != null ? contextBefore : -1;
            int ctxAfter = contextAfter != null ? contextAfter : -1;
            if (contextAround != null && contextAround > 0) {
                ctxBefore = contextAround;
                ctxAfter = contextAround;
            }
            boolean showNum = showLineNumber == null || showLineNumber;
            boolean caseIns = caseInsensitive != null && caseInsensitive;
            int limit = headLimit != null ? headLimit : DEFAULT_HEAD_LIMIT;
            if (limit <= 0) limit = Integer.MAX_VALUE;
            int skip = offset != null ? Math.max(0, offset) : 0;
            boolean multi = multiline != null && multiline;

            // 编译正则
            int flags = 0;
            if (caseIns) flags |= Pattern.CASE_INSENSITIVE;
            if (multi) flags |= Pattern.DOTALL | Pattern.MULTILINE;
            Pattern regex;
            try {
                regex = Pattern.compile(pattern, flags);
            } catch (PatternSyntaxException e) {
                return "正则表达式语法错误: " + e.getMessage();
            }

            // 搜索并收集结果
            Path rootPath = Paths.get(searchPath).toAbsolutePath().normalize();
            if (!Files.exists(rootPath)) {
                return "搜索目录不存在: " + searchPath;
            }

            List<MatchResult> allResults = new ArrayList<>();
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (EXCLUDED_DIRS.contains(dirName) || dirName.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // Glob 过滤
                    if (StringUtils.isNotBlank(glob)) {
                        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
                        if (!matcher.matches(file.getFileName()) && !file.toString().contains(glob.replace("*", ""))) {
                            // 简单 glob 匹配
                            if (!fileMatchGlob(file, glob)) {
                                return FileVisitResult.CONTINUE;
                            }
                        }
                    }

                    // Type 过滤
                    if (StringUtils.isNotBlank(type)) {
                        List<String> exts = TYPE_EXTENSIONS.get(type.toLowerCase());
                        if (exts != null) {
                            String fileName = file.getFileName().toString().toLowerCase();
                            boolean matched = false;
                            for (String ext : exts) {
                                if (fileName.endsWith(ext)) {
                                    matched = true;
                                    break;
                                }
                            }
                            if (!matched) return FileVisitResult.CONTINUE;
                        }
                    }

                    // 内容搜索
                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            Matcher matcher = regex.matcher(lines.get(i));
                            if (matcher.find()) {
                                String relPath = rootPath.relativize(file).toString();
                                allResults.add(new MatchResult(relPath, i + 1, lines.get(i)));
                            }
                        }
                    } catch (IOException ignored) {
                        // 跳过无法读取的文件
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            // 分页
            int totalMatches = allResults.size();
            List<MatchResult> resultsToShow = allResults;
            if (skip > 0) {
                resultsToShow = allResults.subList(Math.min(skip, allResults.size()), allResults.size());
            }

            // 格式化输出
            switch (mode) {
                case "files_with_matches":
                    return formatFilesOnly(resultsToShow, limit, skip, totalMatches, searchPath);
                case "count":
                    return formatCount(resultsToShow, rootPath);
                case "content":
                default:
                    return formatContent(resultsToShow, limit, skip, totalMatches, ctxBefore, ctxAfter, showNum);
            }

        } catch (Exception e) {
            log.error("GrepTool 执行失败", e);
            return "搜索执行失败: " + e.getMessage();
        }
    }

    /** 简单 glob 匹配（支持 braces 扩展，如 {java,xml}） */
    private boolean fileMatchGlob(Path file, String globPattern) {
        String fileName = file.getFileName().toString();

        // 拆分 glob 模式：先按空格拆分，对包含 {} 的模式不拆分内部逗号
        List<String> patterns = new ArrayList<>();
        String[] spaceSplit = globPattern.split("\\s+");
        for (String raw : spaceSplit) {
            if (raw.contains("{") && raw.contains("}")) {
                // braces 模式保留原样
                patterns.add(raw);
            } else {
                // 按逗号拆分不含 braces 的模式
                String[] commaSplit = raw.split(",");
                for (String p : commaSplit) {
                    if (!p.isEmpty()) patterns.add(p);
                }
            }
        }

        for (String pattern : patterns) {
            // 将 glob 转换为正则：. → \\. * → .* ? → .
            // 同时处理 {a,b} → (a|b)
            String regex = pattern
                    .replace(".", "\\.")
                    .replace("*", ".*")
                    .replace("?", ".")
                    .replaceAll("\\{([^}]+)\\}", "($1)");
            if (Pattern.matches(regex, fileName)) {
                return true;
            }
        }
        return false;
    }

    /** 格式化：仅文件路径 */
    private String formatFilesOnly(List<MatchResult> results, int limit, int skip, int total, String searchPath) {
        Set<String> uniqueFiles = new LinkedHashSet<>();
        for (MatchResult r : results) {
            uniqueFiles.add(r.filePath);
            if (uniqueFiles.size() >= limit) break;
        }

        StringBuilder sb = new StringBuilder();
        int shown = Math.min(uniqueFiles.size(), limit);
        if (skip > 0 || total > limit) {
            sb.append(String.format("[结果分页: 限制 %d, 偏移 %d, 共 %d 条]\n", limit, skip, total));
        }
        for (String f : uniqueFiles) {
            sb.append(f).append("\n");
        }
        if (uniqueFiles.size() < results.size() || total > shown) {
            sb.append(String.format("\n[已截断: 显示 %d/%d 个文件]", shown, total));
        }
        return sb.toString();
    }

    /** 格式化：计数模式 */
    private String formatCount(List<MatchResult> results, Path rootPath) {
        Map<String, Integer> countMap = new LinkedHashMap<>();
        for (MatchResult r : results) {
            countMap.merge(r.filePath, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (Map.Entry<String, Integer> e : countMap.entrySet()) {
            sb.append(String.format("%d\t%s\n", e.getValue(), e.getKey()));
            total += e.getValue();
        }
        sb.append(String.format("\n总计: %d 条匹配分布在 %d 个文件中\n", total, countMap.size()));
        return sb.toString();
    }

    /** 格式化：内容模式（含上下文行） */
    private String formatContent(List<MatchResult> results, int limit, int skip,
            int total, int ctxBefore, int ctxAfter, boolean showNum) {
        StringBuilder sb = new StringBuilder();
        if (skip > 0 || total > limit) {
            sb.append(String.format("[结果分页: 限制 %d, 偏移 %d, 共 %d 条]\n", limit, skip, total));
        }

        int shown = 0;
        for (MatchResult r : results) {
            if (shown >= limit) break;

            // 文件头
            if (shown == 0 || !results.get(Math.max(0, shown - 1)).filePath.equals(r.filePath)) {
                if (shown > 0) sb.append("--\n");
                sb.append(r.filePath).append(":\n");
            }

            // 上下文行（简化：仅标注行号，实际需回读文件）
            if (ctxBefore > 0 || ctxAfter > 0) {
                sb.append("  ...\n");
            }

            // 匹配行
            if (showNum) {
                sb.append(String.format("%6d: %s\n", r.lineNumber, r.lineContent));
            } else {
                sb.append(r.lineContent).append("\n");
            }

            if (ctxBefore > 0 || ctxAfter > 0) {
                sb.append("  ...\n");
            }

            shown++;
        }

        if (shown < results.size()) {
            sb.append(String.format("\n[已截断: 显示 %d/%d 条]", shown, total));
        }
        return sb.toString();
    }

    /** 匹配结果内部类 */
    private static class MatchResult {
        final String filePath;
        final int lineNumber;
        final String lineContent;

        MatchResult(String filePath, int lineNumber, String lineContent) {
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.lineContent = lineContent;
        }
    }
}
