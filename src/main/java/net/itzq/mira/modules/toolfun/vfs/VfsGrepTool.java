package net.itzq.mira.modules.toolfun.vfs;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.tool.annotation.Tool;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
import net.itzq.mira.modules.toolfun.ToolFun;
import net.itzq.mira.modules.vfs.VFS;
import net.itzq.mira.modules.vfs.VFSConstants;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * GrepTool - 基于内存 Var_VFS 的正则搜索工具
 *
 * - 支持完整正则表达式语法
 * - 支持 glob 文件过滤和 type 过滤
 * - 支持 content/files_with_matches/count 三种输出模式
 * - 支持上下文行（-B/-A/-C），合并重叠区间，间隙用 -- 标记
 * - 自动排除 VCS 目录（.git, .svn 等）
 * - 默认 head_limit 250 行
 *
 * @author tangzq
 */
@Slf4j
public class VfsGrepTool {

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

    @Tool(name = ToolFun.Tool_VFS_Grep,
          description = "基于正则表达式的内存文件系统搜索工具。\n\n"
                  + "使用说明：\n"
                  + "- 始终使用 Grep 进行内容搜索\n"
                  + "- 支持完整正则表达式语法（例如 \"log.*Error\"、\"function\\s+\\w+\"）\n"
                  + "- 使用 glob 参数过滤文件（例如 \"*.js\"、\"**/*.tsx\"）或 type 参数过滤文件类型\n"
                  + "- 输出模式：\"content\" 显示匹配行内容、\"files_with_matches\" 仅显示文件路径（默认）、\"count\" 显示匹配数量\n"
                  + "- 使用 contextAround/contextBefore/contextAfter 参数显示匹配行的上下文\n"
                  + "- 对于需要多轮搜索的开放式任务，使用 SubAgent 工具代替\n"
                  + "- 正则语法：使用 Java 正则引擎，特殊字符需要转义（如 `interface\\{\\}` 匹配 Go 代码中的 `interface{}`）\n"
                  + "- 多行匹配：默认单行匹配。跨行模式请设置 multiline=true")
    public String grep(
            @ToolParam(description = "正则表达式搜索模式（必填）") String pattern,
            @ToolParam(description = "搜索目录路径，默认为 Var_VFS 根目录", required = false) String path,
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
            // 获取 Var_VFS 实例
            Object vfsObj = contextHolder.getTopTempVariables().get(VFSConstants.Var_VFS);
            if (!(vfsObj instanceof VFS)) {
                return "搜索失败: 虚拟文件系统未初始化";
            }
            VFS vfs = (VFS) vfsObj;
            FileSystem fs = vfs.getFileSystem();

            // 参数默认值
            String searchPath = StringUtils.isBlank(path) ? "/" : path;
            String mode = StringUtils.isBlank(outputMode) ? "files_with_matches" : outputMode;
            int ctxBefore = contextBefore != null ? Math.max(0, contextBefore) : 0;
            int ctxAfter = contextAfter != null ? Math.max(0, contextAfter) : 0;
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
            Path rootPath = fs.getPath(searchPath);
            if (!Files.exists(rootPath)) {
                return "搜索目录不存在: " + searchPath;
            }

            // 按文件缓存全量行内容（用于上下文行提取）
            Map<String, List<String>> fileContents = new LinkedHashMap<>();
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
                        PathMatcher matcher = fs.getPathMatcher("glob:" + glob);
                        if (!matcher.matches(file.getFileName()) && !file.toString().contains(glob.replace("*", ""))) {
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

                    // 内容搜索：读取全部行，缓存后逐行匹配
                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        String relPath = rootPath.relativize(file).toString();
                        boolean hasMatch = false;

                        for (int i = 0; i < lines.size(); i++) {
                            Matcher matcher = regex.matcher(lines.get(i));
                            if (matcher.find()) {
                                allResults.add(new MatchResult(relPath, i + 1));
                                hasMatch = true;
                            }
                        }

                        // 仅缓存有匹配的文件，节省内存
                        if (hasMatch) {
                            fileContents.put(relPath, lines);
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
                    boolean useContext = ctxBefore > 0 || ctxAfter > 0;
                    return formatContent(resultsToShow, fileContents, limit, skip, totalMatches,
                            ctxBefore, ctxAfter, showNum, useContext);
            }

        } catch (Exception e) {
            log.error("GrepTool 执行失败", e);
            return "搜索执行失败: " + e.getMessage();
        }
    }

    /** 简单 glob 匹配（支持 braces 扩展，如 {java,xml}） */
    private boolean fileMatchGlob(Path file, String globPattern) {
        String fileName = file.getFileName().toString();

        List<String> patterns = new ArrayList<>();
        String[] spaceSplit = globPattern.split("\\s+");
        for (String raw : spaceSplit) {
            if (raw.contains("{") && raw.contains("}")) {
                patterns.add(raw);
            } else {
                String[] commaSplit = raw.split(",");
                for (String p : commaSplit) {
                    if (!p.isEmpty()) patterns.add(p);
                }
            }
        }

        for (String pattern : patterns) {
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

    /**
     * 格式化：内容模式（含上下文行）
     *
     * 上下文行处理逻辑：
     * 1. 每个匹配行向前后扩展 ctxBefore/ctxAfter 行
     * 2. 相邻或重叠的区间自动合并（避免重复输出）
     * 3. 区间之间的间隙用 "--" 分隔
     */
    private String formatContent(List<MatchResult> results, Map<String, List<String>> fileContents,
            int limit, int skip, int total, int ctxBefore, int ctxAfter,
            boolean showNum, boolean useContext) {

        StringBuilder sb = new StringBuilder();
        if (skip > 0 || total > limit) {
            sb.append(String.format("[结果分页: 限制 %d, 偏移 %d, 共 %d 条]\n", limit, skip, total));
        }

        // 按文件分组，保持出现顺序
        Map<String, List<Integer>> fileMatches = new LinkedHashMap<>();
        for (MatchResult r : results) {
            fileMatches.computeIfAbsent(r.filePath, k -> new ArrayList<>()).add(r.lineNumber);
        }

        int shown = 0;
        String lastFile = null;

        for (Map.Entry<String, List<Integer>> entry : fileMatches.entrySet()) {
            if (shown >= limit) break;

            String filePath = entry.getKey();
            List<Integer> matchLines = entry.getValue();
            List<String> lines = fileContents.get(filePath);
            if (lines == null) continue;

            int totalLines = lines.size();

            if (!useContext) {
                // 无上下文：逐行输出匹配行（保持旧行为兼容）
                if (lastFile != null) sb.append("--\n");
                sb.append(filePath).append(":\n");
                lastFile = filePath;

                for (int lineNum : matchLines) {
                    if (shown >= limit) break;
                    appendLine(sb, lines, lineNum, totalLines, showNum);
                    shown++;
                }
            } else {
                // 有上下文：合并区间后输出
                List<int[]> mergedRanges = mergeContextRanges(matchLines, ctxBefore, ctxAfter, totalLines);
                Set<Integer> matchLineSet = new HashSet<>(matchLines);
                boolean isFirstRange = true;

                for (int[] range : mergedRanges) {
                    if (shown >= limit) break;

                    // 文件头
                    if (lastFile == null || !lastFile.equals(filePath)) {
                        if (lastFile != null) sb.append("--\n");
                        sb.append(filePath).append(":\n");
                        lastFile = filePath;
                    }

                    // 区间间的间隙分隔符（同文件内多个不连续区间）
                    if (!isFirstRange) {
                        sb.append("--\n");
                    }
                    isFirstRange = false;

                    // 输出区间内的每一行
                    for (int lineNum = range[0]; lineNum <= range[1]; lineNum++) {
                        if (shown >= limit) break;
                        boolean isMatch = matchLineSet.contains(lineNum);
                        appendContextLine(sb, lines, lineNum, totalLines, showNum, isMatch);
                        if (isMatch) shown++;
                    }
                }
            }
        }

        if (shown < total) {
            sb.append(String.format("\n[已截断: 显示 %d/%d 条匹配]", shown, total));
        }
        return sb.toString();
    }

    /**
     * 将匹配行号列表 + 上下文行数 → 合并后的连续区间列表
     * 例如匹配行 [5, 10, 12]，ctxBefore=2, ctxAfter=2 → [[3,7],[8,14]]（8-7<=1 所以合并）
     */
    private List<int[]> mergeContextRanges(List<Integer> matchLines, int ctxBefore, int ctxAfter, int totalLines) {
        List<int[]> ranges = new ArrayList<>();
        for (int line : matchLines) {
            int start = Math.max(1, line - ctxBefore);
            int end = Math.min(totalLines, line + ctxAfter);
            ranges.add(new int[]{start, end});
        }

        // 按起始行排序
        ranges.sort(Comparator.comparingInt(a -> a[0]));

        // 合并重叠或相邻区间（间隙 <= 1 行视为连续）
        List<int[]> merged = new ArrayList<>();
        for (int[] range : ranges) {
            if (!merged.isEmpty()) {
                int[] last = merged.get(merged.size() - 1);
                if (range[0] <= last[1] + 1) {
                    // 重叠或相邻，合并
                    last[1] = Math.max(last[1], range[1]);
                    continue;
                }
            }
            merged.add(new int[]{range[0], range[1]});
        }
        return merged;
    }

    /** 输出一行（无上下文模式，匹配行） */
    private void appendLine(StringBuilder sb, List<String> lines, int lineNum, int totalLines, boolean showNum) {
        if (lineNum < 1 || lineNum > totalLines) return;
        String content = lines.get(lineNum - 1);
        if (showNum) {
            sb.append(String.format("%6d: %s\n", lineNum, content));
        } else {
            sb.append(content).append("\n");
        }
    }

    /** 输出一行（上下文模式，匹配行用 : 标记，上下文行用 - 标记） */
    private void appendContextLine(StringBuilder sb, List<String> lines, int lineNum,
            int totalLines, boolean showNum, boolean isMatch) {
        if (lineNum < 1 || lineNum > totalLines) return;
        String content = lines.get(lineNum - 1);
        if (showNum) {
            // 匹配行用 ":"，上下文行用 "-"（类似 grep -n 的惯例）
            String marker = isMatch ? ":" : "-";
            sb.append(String.format("%6d%s %s\n", lineNum, marker, content));
        } else {
            sb.append(content).append("\n");
        }
    }

    /** 匹配结果内部类（仅存储文件路径和行号，行内容从缓存读取） */
    private static class MatchResult {
        final String filePath;
        final int lineNumber;

        MatchResult(String filePath, int lineNumber) {
            this.filePath = filePath;
            this.lineNumber = lineNumber;
        }
    }
}
