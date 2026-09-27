package net.itzq.mira.modules.toolfun.code;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.tool.annotation.Tool;
import net.itzq.mira.modules.ai.tool.annotation.ToolParam;
import net.itzq.mira.modules.ai.tool.ToolCallResult;
import net.itzq.mira.modules.toolfun.ToolFun;
import net.itzq.mira.core.utils.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.itzq.mira.modules.toolfun.code.FileReadTool.BLOCKED_PATHS;

/**
 * FileEditTool - 精确字符串替换工具（带多级容错匹配 + 编码/换行保真）
 *
 * 匹配采用“逐级放宽”的级联策略：
 * <pre>
 *   L0 exact                    逐字节精确匹配
 *   L1 quote_normalized         弯引号 → 直引号（等长，下标可 1:1 映射回原文）
 *   L2 line_number_prefix_stripped  剥掉 Read 输出的 "  42| " / "42: " / "42\t" 行号前缀
 *   L3 escape_normalized        还原 \n \t \r \" \' \` \\ \$ 等字面转义
 *   L4 unicode_escape_normalized 还原 U+XXXX 形式的 Unicode 转义
 *   L5 line_trimmed             逐行 trim 后比较（容忍行首尾空白差异）
 *   L6 indentation_flexible     去掉整块公共缩进后比较（容忍 Tab/空格缩进差异）
 *   L7 block_anchor             首末行锚定 + 中间行相似度 >= 0.8（大块兜底）
 * </pre>
 *
 * 关键不变量：每一级匹配都必须返回“文件中真实存在的子串 + 下标”，
 * 绝不能返回入参 old_string —— 否则会出现“匹配成功却替换不了”的假成功。
 *
 * 其他约定：
 * - 读回写保真：BOM（UTF-8/UTF-16LE/UTF-16BE）、字符集（UTF-8/GBK 探测）、换行风格（大小写占优）原样保留
 * - old_string 必须在文件中唯一（除非 replace_all=true）
 * - 编辑前必须先 Read（提示约束）
 *
 * @author tangzq
 */
@Slf4j
public class FileEditTool {

    private static final long MAX_FILE_SIZE = 1024 * 1024 * 1024; // 1 GiB

    /** 块锚点策略的中间行相似度下限 */
    private static final double BLOCK_ANCHOR_MIN_SIMILARITY = 0.8;

    /** replace_all=true 时跳过的“宽松策略”（它们可能匹配到远超预期的范围） */
    private static final Set<Strategy> BROAD_STRATEGIES = EnumSet.of(
            Strategy.LINE_TRIMMED, Strategy.INDENTATION_FLEXIBLE, Strategy.BLOCK_ANCHOR);

    /** Read 输出的行号前缀： "    42| code" */
    private static final Pattern READ_PREFIX_BAR = Pattern.compile("^\\s*\\d+\\|\\s?");
    /** Read 输出的行号前缀变体： "42: code" */
    private static final Pattern READ_PREFIX_COLON = Pattern.compile("^\\d+: ");
    /** Read 输出的行号前缀变体： "42\tcode" */
    private static final Pattern READ_PREFIX_TAB = Pattern.compile("^\\d+\\t");

    /** 可见字符转义： \n \t \r \" \' \` \\ \$ */
    private static final Pattern VISIBLE_ESCAPE = Pattern.compile("\\\\([ntr\"'`\\\\$])");
    /** Unicode 转义：已转义的反斜杠（原样保留）或 U+XXXX 形式的转义序列 */
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("(\\\\\\\\)|\\\\u([0-9a-fA-F]{4})");

    @Tool(name = ToolFun.TOOL_Edit,
          display = "编辑文件",
          description = "在文件中执行精确字符串替换。\n\n"
                    + "使用说明：\n"
                    + "- 编辑前必须先用 Read 工具读取文件。—— 如果未先读取就编辑，工具将报错\n"
                    + "- 编辑 Read 工具输出中的文本时，必须保留精确的缩进（制表符/空格）\n"
                    + "- Read 工具输出的行号前缀格式为：行号 + 竖线。尽量不要将此前缀包含在 old_string 中（工具虽会自动剥离，但显式去掉更可靠）\n"
                    + "- 优先使用 Edit 编辑已有文件，而不是用 Write 重写。仅在新建文件或完全重写时才用 Write\n"
                    + "- 仅当用户明确要求时才使用 emoji\n"
                    + "- 如果 old_string 在文件中不唯一，编辑将失败。此时请提供更长的上下文使 old_string 唯一，或设置 replace_all=true\n"
                    + "- 使用 replace_all=true 可替换文件中所有匹配项\n"
                    + "- 轻微差异（引号风格、行尾空白、缩进、转义写法、行号前缀）会被自动容错，但仍应尽量提供与文件一致的原文\n"
                    + "- 生成参数时遵循先输出oldString再newString参数的顺序\n"
                    + "- 不要通过 Bash 工具使用 sed/awk 来编辑文件"
    )
    public String fileEdit(
            @ToolParam(description = "要编辑的文件绝对路径（必填）") String filePath,
            @ToolParam(description = "要被替换的文本字符串（必填，必须在文件中唯一存在）") String oldString,
            @ToolParam(description = "替换后的新文本字符串（必填，必须与 old_string 不同）") String newString,
            @ToolParam(description = "是否替换所有匹配项，默认 false", required = false) Boolean replaceAll,
            AgentContextHolder contextHolder) {

        try {
            Path path = Paths.get(filePath).toAbsolutePath().normalize();

            // 安全检查：设备文件（含 /proc/*/fd/* 别名）
            String pathStr = path.toString();
            String fileName = path.getFileName().toString().toUpperCase();
            for (String blocked : BLOCKED_PATHS) {
                if (pathStr.contains(blocked) || fileName.equals(blocked)) {
                    return ToolCallResult.error("安全限制: 无法读取设备文件或特殊文件: " + filePath);
                }
            }

            // 验证 1: 文件存在
            if (!Files.exists(path)) {
                return ToolCallResult.error(String.format("编辑失败: 文件不存在 —— %s", filePath));
            }

            // 验证 2: 文件大小
            long fileSize = Files.size(path);
            if (fileSize > MAX_FILE_SIZE) {
                return ToolCallResult.error(String.format("编辑失败: 文件过大 (%d bytes)，超过 1 GiB 限制", fileSize));
            }

            // 验证 3: 新旧不能相同
            if (oldString.equals(newString)) {
                return ToolCallResult.error("编辑失败: old_string 和 new_string 相同，没有需要修改的内容");
            }

            // 验证 4: oldString 不能为空
            if (StringUtils.isBlank(oldString)) {
                return ToolCallResult.error("编辑失败: old_string 不能为空");
            }

            // 验证 5: Notebook 文件须使用 NotebookEdit 工具
            if (filePath.toLowerCase().endsWith(".ipynb")) {
                return ToolCallResult.error("编辑失败: 文件是 Jupyter Notebook。请使用 NotebookEdit 工具编辑此文件。");
            }

            // 读取原始字节（用于 BOM / 编码 / 换行风格判断）
            byte[] rawBytes;
            try {
                rawBytes = Files.readAllBytes(path);
            } catch (IOException e) {
                return ToolCallResult.error("编辑失败: 无法读取文件 —— " + e.getMessage());
            }

            // 解码：识别 BOM + 字符集（UTF-8 / UTF-16LE / UTF-16BE / GBK 回退），含 NUL 视为二进制
            TextFileCodec.Decoded decoded = TextFileCodec.decode(rawBytes);
            if (decoded == null) {
                return ToolCallResult.error("编辑失败: 文件不是可编辑的文本（二进制或不受支持的编码）—— " + filePath);
            }

            // 统一 LF 归一化：内容与输入都走同一套规则，匹配与写回都在 LF 域进行
            String lfContent = TextFileCodec.normalizeLineEndings(decoded.text);
            String lfOld = TextFileCodec.normalizeLineEndings(oldString);
            String lfNew = TextFileCodec.normalizeLineEndings(newString);

            boolean replaceAllFlag = replaceAll != null && replaceAll;

            // 多级容错匹配：返回文件中真实子串 + 命中策略
            MatchResult match = findEditMatch(lfContent, lfOld, replaceAllFlag);
            if (match.status == MatchStatus.NOT_FOUND) {
                return ToolCallResult.error(String.format(
                        "编辑失败: old_string 在文件中未找到（已尝试精确/引号/行号前缀/转义/行 trim/缩进/块锚点等容错匹配）。\n"
                                + "请确认 old_string 与文件内容一致（包括空格、缩进和标点符号）。\n"
                                + "old_string: \"%s\"",
                        truncateForDisplay(lfOld, 200)));
            }
            if (match.status == MatchStatus.AMBIGUOUS) {
                return ToolCallResult.error(String.format(
                        "编辑失败: 匹配到 %d 处等价但不同的候选，无法确定目标。\n"
                                + "请提供更多上下文使 old_string 唯一，或设置 replace_all=true。\n\n"
                                + "old_string: \"%s\"",
                        match.candidateCount,
                        truncateForDisplay(lfOld, 200)));
            }

            String actualOld = match.actualString;
            int occurrences = countOccurrences(lfContent, actualOld);
            if (occurrences > 1 && !replaceAllFlag) {
                return ToolCallResult.error(String.format(
                        "编辑失败: 找到 %d 处匹配，但 replace_all 为 false。\n"
                                + "要替换所有匹配项，请设置 replace_all=true。\n"
                                + "要仅替换其中一处，请提供更多上下文使 old_string 唯一。\n"
                                + "匹配位置（行号）: %s\n\n"
                                + "匹配的字符串: \"%s\"",
                        occurrences,
                        joinInts(lineNumbersOf(lfContent, actualOld)),
                        truncateForDisplay(actualOld, 200)));
            }

            // 新文本归一化：escape 策略下需把字面转义还原；再按文件原有引号风格改写
            String replacement = normalizeReplacementForMatch(match.strategy, lfNew);
            replacement = preserveQuoteStyle(lfOld, actualOld, replacement);

            String editedLf = applyEditToContent(lfContent, actualOld, replacement, replaceAllFlag);

            // 假成功守卫：匹配到了但内容没变，必须显式报失败
            if (editedLf.equals(lfContent)) {
                return ToolCallResult.error("编辑失败: 匹配成功，但替换后内容未发生变化（old_string 与 new_string 等价）");
            }

            // 写回：恢复换行风格 + 原字符集 + 原 BOM
            String output = TextFileCodec.restoreLineEndings(editedLf, decoded.lineEndings);
            byte[] outputBytes = TextFileCodec.encode(output, decoded.charset, decoded.bom);
            Files.write(path, outputBytes);
            File file = path.toFile();
            file.setExecutable(true, false);
            file.setReadable(true, false);
            file.setWritable(true, false);

            String diffPreview = generateDiffPreview(lfContent, editedLf, actualOld, replacement);

            return ToolCallResult.successUnlessMarked(String.format(
                    "文件编辑成功: %s\n%s 处匹配已替换（匹配策略: %s）\n\n%s",
                    filePath,
                    replaceAllFlag ? "所有 " + occurrences : "1",
                    match.strategy.name().toLowerCase(),
                    diffPreview));

        } catch (Exception e) {
            log.error("FileEditTool 执行失败", e);
            return ToolCallResult.error("编辑失败: " + e);
        }
    }

    // ==================================================================
    // 多级容错匹配
    // ==================================================================

    /** 匹配策略，顺序即优先级（由严格到宽松） */
    private enum Strategy {
        EXACT,
        QUOTE_NORMALIZED,
        LINE_NUMBER_PREFIX_STRIPPED,
        ESCAPE_NORMALIZED,
        UNICODE_ESCAPE_NORMALIZED,
        LINE_TRIMMED,
        INDENTATION_FLEXIBLE,
        BLOCK_ANCHOR
    }

    private enum MatchStatus {MATCHED, AMBIGUOUS, NOT_FOUND}

    /** 匹配结果：真实子串 + 命中策略 + 候选数 */
    private static final class MatchResult {
        final MatchStatus status;
        final String actualString;
        final Strategy strategy;
        final int candidateCount;

        private MatchResult(MatchStatus status, String actualString, Strategy strategy, int candidateCount) {
            this.status = status;
            this.actualString = actualString;
            this.strategy = strategy;
            this.candidateCount = candidateCount;
        }

        static MatchResult matched(String actualString, Strategy strategy, int candidateCount) {
            return new MatchResult(MatchStatus.MATCHED, actualString, strategy, candidateCount);
        }

        static MatchResult ambiguous(Strategy strategy, int candidateCount) {
            return new MatchResult(MatchStatus.AMBIGUOUS, null, strategy, candidateCount);
        }

        static MatchResult notFound() {
            return new MatchResult(MatchStatus.NOT_FOUND, null, null, 0);
        }
    }

    /** 一个候选：文件中真实存在的子串 + 起始下标 */
    private static final class Candidate {
        final String value;
        final int index;

        Candidate(String value, int index) {
            this.value = value;
            this.index = index;
        }
    }

    /**
     * 逐级放宽的匹配级联。每级都必须返回“文件中真实子串 + 下标”。
     */
    private MatchResult findEditMatch(String content, String search, boolean replaceAll) {
        // L0 精确
        List<Candidate> exact = collectSubstringCandidates(content, search);
        if (!exact.isEmpty()) {
            return toMatchResult(Strategy.EXACT, exact);
        }

        Strategy[] order = {
                Strategy.QUOTE_NORMALIZED,
                Strategy.LINE_NUMBER_PREFIX_STRIPPED,
                Strategy.ESCAPE_NORMALIZED,
                Strategy.UNICODE_ESCAPE_NORMALIZED,
                Strategy.LINE_TRIMMED,
                Strategy.INDENTATION_FLEXIBLE,
                Strategy.BLOCK_ANCHOR,
        };
        for (Strategy strategy : order) {
            // replace_all 时跳过宽松策略，避免匹配到远超预期的范围
            if (replaceAll && BROAD_STRATEGIES.contains(strategy)) {
                continue;
            }
            List<Candidate> candidates = collectCandidates(strategy, content, search);
            if (!candidates.isEmpty()) {
                return toMatchResult(strategy, candidates);
            }
        }
        return MatchResult.notFound();
    }

    private List<Candidate> collectCandidates(Strategy strategy, String content, String search) {
        switch (strategy) {
            case QUOTE_NORMALIZED:
                return collectNormalizedCandidates(content, search, FileEditTool::normalizeQuotes);
            case LINE_NUMBER_PREFIX_STRIPPED:
                return collectLineNumberPrefixCandidates(content, search);
            case ESCAPE_NORMALIZED:
                return collectEscapeNormalizedCandidates(content, search);
            case UNICODE_ESCAPE_NORMALIZED:
                return collectUnicodeEscapeNormalizedCandidates(content, search);
            case LINE_TRIMMED:
                return collectLineTrimmedCandidates(content, search);
            case INDENTATION_FLEXIBLE:
                return collectIndentationFlexibleCandidates(content, search);
            case BLOCK_ANCHOR:
                return collectBlockAnchorCandidates(content, search);
            default:
                return collectSubstringCandidates(content, search);
        }
    }

    /**
     * 把候选列表收敛成结果：候选取值必须唯一，否则视为歧义。
     */
    private MatchResult toMatchResult(Strategy strategy, List<Candidate> candidates) {
        LinkedHashSet<String> uniqueValues = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            uniqueValues.add(candidate.value);
        }
        if (uniqueValues.size() != 1) {
            return MatchResult.ambiguous(strategy, candidates.size());
        }
        return MatchResult.matched(uniqueValues.iterator().next(), strategy, candidates.size());
    }

    // ---- 各级候选收集 ----

    private List<Candidate> collectSubstringCandidates(String content, String search) {
        List<Candidate> candidates = new ArrayList<>();
        if (search.isEmpty()) {
            return candidates;
        }
        int position = 0;
        while (position <= content.length()) {
            int index = content.indexOf(search, position);
            if (index == -1) {
                break;
            }
            candidates.add(new Candidate(search, index));
            position = index + Math.max(search.length(), 1);
        }
        return candidates;
    }

    /**
     * 等长归一化匹配：normalize 必须保证 1 字符↔1 字符，下标才能直接映射回原文。
     * ★ 这里精确定位后从原文切片，返回真实子串（而非 search），是修复“假成功”的关键。
     */
    private List<Candidate> collectNormalizedCandidates(String content, String search,
                                                        UnaryOperator<String> normalize) {
        List<Candidate> candidates = new ArrayList<>();
        String normalizedContent = normalize.apply(content);
        if (normalizedContent.length() != content.length()) {
            return candidates; // 非等长归一化不允许走此路径，防止下标错位
        }
        String normalizedSearch = normalize.apply(search);
        if (normalizedSearch.isEmpty()) {
            return candidates;
        }
        int position = 0;
        while (position <= normalizedContent.length()) {
            int index = normalizedContent.indexOf(normalizedSearch, position);
            if (index == -1) {
                break;
            }
            candidates.add(new Candidate(content.substring(index, index + search.length()), index));
            position = index + Math.max(normalizedSearch.length(), 1);
        }
        return candidates;
    }

    private List<Candidate> collectLineNumberPrefixCandidates(String content, String search) {
        String stripped = stripReadLineNumberPrefixes(search);
        if (stripped == null || stripped.equals(search)) {
            return Collections.emptyList();
        }
        return collectSubstringCandidates(content, stripped);
    }

    private List<Candidate> collectEscapeNormalizedCandidates(String content, String search) {
        String unescaped = unescapeVisibleCharacters(search);
        if (unescaped.equals(search)) {
            return Collections.emptyList();
        }
        return collectSubstringCandidates(content, unescaped);
    }

    private List<Candidate> collectUnicodeEscapeNormalizedCandidates(String content, String search) {
        String unescaped = unescapeUnicodeCharacters(search);
        if (unescaped.equals(search)) {
            return Collections.emptyList();
        }
        return collectSubstringCandidates(content, unescaped);
    }

    private List<Candidate> collectLineTrimmedCandidates(String content, String search) {
        String[] contentLines = content.split("\n", -1);
        String[] searchLines = trimTrailingEmptyLine(search.split("\n", -1));
        List<Candidate> candidates = new ArrayList<>();
        if (searchLines.length == 0) {
            return candidates;
        }
        for (int i = 0; i + searchLines.length <= contentLines.length; i++) {
            boolean hit = true;
            for (int j = 0; j < searchLines.length; j++) {
                if (!contentLines[i + j].trim().equals(searchLines[j].trim())) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                candidates.add(blockCandidate(contentLines, i, searchLines.length));
            }
        }
        return candidates;
    }

    private List<Candidate> collectIndentationFlexibleCandidates(String content, String search) {
        String[] contentLines = content.split("\n", -1);
        String[] searchLines = trimTrailingEmptyLine(search.split("\n", -1));
        List<Candidate> candidates = new ArrayList<>();
        if (searchLines.length < 2) {
            return candidates;
        }
        String normalizedSearch = removeCommonIndent(searchLines);
        for (int i = 0; i + searchLines.length <= contentLines.length; i++) {
            String[] block = Arrays.copyOfRange(contentLines, i, i + searchLines.length);
            if (!removeCommonIndent(block).equals(normalizedSearch)) {
                continue;
            }
            candidates.add(blockCandidate(contentLines, i, searchLines.length));
        }
        return candidates;
    }

    private List<Candidate> collectBlockAnchorCandidates(String content, String search) {
        String[] contentLines = content.split("\n", -1);
        String[] searchLines = trimTrailingEmptyLine(search.split("\n", -1));
        List<Candidate> candidates = new ArrayList<>();
        if (searchLines.length < 3) {
            return candidates;
        }
        String first = searchLines[0].trim();
        String last = searchLines[searchLines.length - 1].trim();
        for (int i = 0; i + searchLines.length <= contentLines.length; i++) {
            String[] block = Arrays.copyOfRange(contentLines, i, i + searchLines.length);
            if (!block[0].trim().equals(first)) {
                continue;
            }
            if (!block[block.length - 1].trim().equals(last)) {
                continue;
            }
            if (averageMiddleSimilarity(block, searchLines) < BLOCK_ANCHOR_MIN_SIMILARITY) {
                continue;
            }
            candidates.add(blockCandidate(contentLines, i, searchLines.length));
        }
        return candidates;
    }

    // ---- 行块工具 ----

    private Candidate blockCandidate(String[] lines, int startLine, int lineCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lineCount; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[startLine + i]);
        }
        return new Candidate(sb.toString(), offsetForLine(lines, startLine));
    }

    private int offsetForLine(String[] lines, int lineIndex) {
        int offset = 0;
        for (int i = 0; i < lineIndex; i++) {
            offset += lines[i].length() + 1;
        }
        return offset;
    }

    private String[] trimTrailingEmptyLine(String[] lines) {
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            return Arrays.copyOf(lines, lines.length - 1);
        }
        return lines;
    }

    /** 去掉整块的公共缩进前缀（空行不参与计算，也不被裁剪） */
    private String removeCommonIndent(String[] lines) {
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
                indent++;
            }
            minIndent = Math.min(minIndent, indent);
        }
        if (minIndent == Integer.MAX_VALUE) {
            minIndent = 0;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines[i];
            sb.append(line.trim().isEmpty() ? line : line.substring(Math.min(minIndent, line.length())));
        }
        return sb.toString();
    }

    private double averageMiddleSimilarity(String[] actual, String[] expected) {
        if (actual.length <= 2) {
            return 1.0;
        }
        double total = 0;
        int count = 0;
        for (int i = 1; i < actual.length - 1; i++) {
            total += lineSimilarity(actual[i].trim(), expected[i].trim());
            count++;
        }
        return count == 0 ? 1.0 : total / count;
    }

    private double lineSimilarity(String left, String right) {
        if (left.equals(right)) {
            return 1.0;
        }
        int maxLength = Math.max(left.length(), right.length());
        if (maxLength == 0) {
            return 1.0;
        }
        return 1.0 - (double) levenshtein(left, right) / maxLength;
    }

    /** 经典 Levenshtein 编辑距离（滚动数组，空间 O(min)） */
    private int levenshtein(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return Math.max(left.length(), right.length());
        }
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    // ==================================================================
    // 归一化
    // ==================================================================

    /** 弯引号 → 直引号（等长，1 字符↔1 字符） */
    private static String normalizeQuotes(String value) {
        return value
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201a', '\'').replace('\u201b', '\'')
                .replace('\u201c', '"').replace('\u201d', '"')
                .replace('\u201e', '"').replace('\u201f', '"')
                .replace('\u2032', '\'').replace('\u2033', '"');
    }

    /** 剥离 Read 输出的行号前缀：所有行都能剥掉才生效，否则视为普通文本 */
    private String stripReadLineNumberPrefixes(String search) {
        String[] lines = search.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String stripped = stripOneLinePrefix(lines[i]);
            if (stripped == null) {
                return null;
            }
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(stripped);
        }
        return sb.toString();
    }

    private String stripOneLinePrefix(String line) {
        Matcher bar = READ_PREFIX_BAR.matcher(line);
        if (bar.find()) {
            return line.substring(bar.end());
        }
        Matcher colon = READ_PREFIX_COLON.matcher(line);
        if (colon.find()) {
            return line.substring(colon.end());
        }
        Matcher tab = READ_PREFIX_TAB.matcher(line);
        if (tab.find()) {
            return line.substring(tab.end());
        }
        return null;
    }

    /** 还原字面转义： \n \t \r \" \' \` \\ \$ */
    private String unescapeVisibleCharacters(String search) {
        Matcher matcher = VISIBLE_ESCAPE.matcher(search);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement;
            switch (token) {
                case "n":
                    replacement = "\n";
                    break;
                case "t":
                    replacement = "\t";
                    break;
                case "r":
                    replacement = "\r";
                    break;
                default:
                    // \' \" \` \\ \$ 都是“去掉反斜杠”即可
                    replacement = token;
                    break;
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 还原 U+XXXX 形式的转义（已转义的反斜杠原样保留） */
    private String unescapeUnicodeCharacters(String search) {
        Matcher matcher = UNICODE_ESCAPE.matcher(search);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                int code = Integer.parseInt(matcher.group(2), 16);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) code)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 宽松归一化策略下，新文本也需要做同样的还原：
     * - ESCAPE_NORMALIZED：old 靠还原  等字面转义才匹配上，new 也必须同样还原；
     * - UNICODE_ESCAPE_NORMALIZED：old 靠还原 U+XXXX 形式的 unicode 转义才匹配上，new 同理。
     * 否则替换结果会把转义写法原样落盘。
     */
    private String normalizeReplacementForMatch(Strategy strategy, String newString) {
        if (strategy == Strategy.ESCAPE_NORMALIZED) {
            return unescapeVisibleCharacters(newString);
        }
        if (strategy == Strategy.UNICODE_ESCAPE_NORMALIZED) {
            return unescapeUnicodeCharacters(newString);
        }
        return newString;
    }

    /**
     * 保留文件中的引号风格：当 old_string 通过归一化才匹配成功时，
     * 把 new_string 中的直引号按文件原风格改写为弯引号。
     *
     * 方向判定不用"前后字符"启发式去猜（会把 xxxx"< " 判成 ”<“ 这种方向反转），
     * 而是按出现顺序与 actualOldString 中的弯引号一一对应：
     * new_string 的第 j 个直引号 ← actualOldString 的第 j 个弯引号的方向；
     * 只有超出对应关系的新增引号才退回上下文启发式。
     */
    private String preserveQuoteStyle(String oldString, String actualOldString, String newString) {
        if (oldString.equals(actualOldString)) {
            return newString;
        }
        String result = applyCurlyQuotesByOrder(newString, actualOldString, '"');
        result = applyCurlyQuotesByOrder(result, actualOldString, '\'');
        return result;
    }

    /** 弯引号 → 直引号 的归一化映射（与 normalizeQuotes 保持一致） */
    private static boolean isCurlyDouble(char c) {
        return c == '\u201c' || c == '\u201d' || c == '\u201e' || c == '\u201f' || c == '\u2033';
    }

    private static boolean isCurlySingle(char c) {
        return c == '\u2018' || c == '\u2019' || c == '\u201a' || c == '\u201b' || c == '\u2032';
    }

    private static char openCurlyFor(char straight) {
        return straight == '"' ? '\u201c' : '\u2018';
    }

    private static char closeCurlyFor(char straight) {
        return straight == '"' ? '\u201d' : '\u2019';
    }

    /**
     * 把 value 中的 straight 直引号，按 actualOld 中同序号弯引号的方向改写为弯引号。
     * actualOld 中没有可对应的弯引号时原样返回。
     */
    private String applyCurlyQuotesByOrder(String value, String actualOld, char straight) {
        if (value.indexOf(straight) < 0) {
            return value;
        }
        List<Character> directions = new ArrayList<>(actualOld.length());
        for (int i = 0; i < actualOld.length(); i++) {
            char c = actualOld.charAt(i);
            if (straight == '"' ? isCurlyDouble(c) : isCurlySingle(c)) {
                directions.add(c);
            }
        }
        if (directions.isEmpty()) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        int quoteIndex = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != straight) {
                sb.append(c);
                continue;
            }
            if (quoteIndex < directions.size()) {
                sb.append(directions.get(quoteIndex));
            } else {
                sb.append(fallbackCurlyDirection(value, i, straight));
            }
            quoteIndex++;
        }
        return sb.toString();
    }

    /** 超出 old 对应关系的直引号：退回上下文启发式（词中撇号优先按右单引号处理） */
    private char fallbackCurlyDirection(String value, int index, char straight) {
        if (straight == '\'') {
            char previous = index > 0 ? value.charAt(index - 1) : '\0';
            char next = index + 1 < value.length() ? value.charAt(index + 1) : '\0';
            if (Character.isLetter(previous) && Character.isLetter(next)) {
                return '\u2019'; // don't / it's 词中撇号
            }
        }
        return isOpeningQuoteContext(value, index) ? openCurlyFor(straight) : closeCurlyFor(straight);
    }

    private boolean isOpeningQuoteContext(String value, int index) {
        if (index == 0) {
            return true;
        }
        char previous = value.charAt(index - 1);
        return previous == ' ' || previous == '\t' || previous == '\n' || previous == '\r'
                || previous == '(' || previous == '[' || previous == '{'
                || previous == '\u2014' || previous == '\u2013';
    }

    // ==================================================================
    // 替换与统计
    // ==================================================================

    /**
     * 应用替换。 当 new_string 为空（删除）时，
     * 若 old_string 不以换行结尾但文件里那行带换行，则连同换行一起删除，避免留下空行。
     */
    private String applyEditToContent(String content, String oldString, String newString, boolean replaceAll) {
        if (!newString.isEmpty()) {
            return replaceLiteral(content, oldString, newString, replaceAll);
        }
        String search = (!oldString.endsWith("\n") && content.contains(oldString + "\n"))
                ? oldString + "\n"
                : oldString;
        return replaceLiteral(content, search, newString, replaceAll);
    }

    /** 字面量替换（String.replace 不做正则解释，new_string 含 $ 也安全） */
    private String replaceLiteral(String content, String search, String replacement, boolean replaceAll) {
        if (replaceAll) {
            return content.replace(search, replacement);
        }
        int index = content.indexOf(search);
        if (index < 0) {
            return content;
        }
        return content.substring(0, index) + replacement + content.substring(index + search.length());
    }

    private int countOccurrences(String text, String search) {
        if (search.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(search, index)) != -1) {
            count++;
            index += search.length();
        }
        return count;
    }

    /** 每一处匹配所在的 1-based 行号，便于模型自行消歧 */
    private List<Integer> lineNumbersOf(String content, String search) {
        List<Integer> lineNumbers = new ArrayList<>();
        if (search.isEmpty()) {
            return lineNumbers;
        }
        int index = 0;
        while ((index = content.indexOf(search, index)) != -1) {
            lineNumbers.add(1 + countOccurrences(content.substring(0, index), "\n"));
            index += Math.max(search.length(), 1);
        }
        return lineNumbers;
    }

    private String joinInts(List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    // ==================================================================
    // 编码 / BOM / 换行保真 —— 统一由 TextFileCodec 提供（Read/Write/Edit 共用）
    // ==================================================================

    // ==================================================================
    // 展示
    // ==================================================================

    /** 生成简洁 diff 预览 */
    private String generateDiffPreview(String oldContent, String newContent,
            String oldStr, String newStr) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- 修改预览 ---\n");

        int oldIndex = oldContent.indexOf(oldStr);
        if (oldIndex >= 0) {
            int contextStart = Math.max(0, oldIndex - 80);
            int contextEnd = Math.min(oldContent.length(), oldIndex + oldStr.length() + 80);

            String before = oldContent.substring(contextStart, oldIndex);
            String after = oldContent.substring(oldIndex + oldStr.length(), contextEnd);

            sb.append("上下文:\n");
            sb.append("  ...").append(truncateForDisplay(before, 100)).append("\n");
            sb.append("- ").append(truncateForDisplay(oldStr, 200)).append("\n");
            sb.append("+ ").append(truncateForDisplay(newStr, 200)).append("\n");
            sb.append("  ...").append(truncateForDisplay(after, 100)).append("\n");
        }

        return sb.toString();
    }

    private String truncateForDisplay(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...";
    }
}
