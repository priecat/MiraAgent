package net.itzq.mira.modules.toolfun.vfs;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.tool.annotation.Tool;
import net.itzq.mira.modules.ai.tool.annotation.ToolParam;
import net.itzq.mira.modules.toolfun.ToolFun;
import net.itzq.mira.modules.vfs.VFS;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FileEditTool - 精确字符串替换工具（基于内存 Var_VFS）
 *
 * - 精确字符串匹配替换（先精确匹配，再引号规范化匹配）
 * - old_string 必须在文件中唯一（除非 replace_all=true）
 * - 编辑前必须先 Read（提示约束）
 * - 提供 diff 预览
 * - 换行符 CRLF→LF 归一化
 *
 * @author tangzq
 */
@Slf4j
public class VfsFileEditTool {

    private static final long MAX_FILE_SIZE = 1024 * 1024 * 1024; // 1 GiB

    @Tool(name = ToolFun.Tool_VFS_File_Edit,
          display = "编辑文件",
          description = "在文件中执行精确字符串替换。\n\n" + "使用说明：\n" + "- 编辑前必须先用 " + ToolFun.Tool_VFS_File_Read + " " + "工具读取文件。—— "
                  + "如果未先读取就编辑，工具将报错\n" + "- 编辑 " + ToolFun.Tool_VFS_File_Read + " 工具输出中的文本时，必须保留精确的缩进（制表符/空格）\n" + "- "
                  + ToolFun.Tool_VFS_File_Read + " 工具输出的行号前缀格式为：行号 + 竖线。不要将此前缀包含在 old_string 或 new_string 中\n" + "- 优先使用 "
                  + ToolFun.Tool_VFS_File_Edit + " 编辑已有文件，而不是用 " + ToolFun.Tool_VFS_File_Write
                  + " 重写。仅在新建文件或完全重写时才用 " + ToolFun.Tool_VFS_File_Write + "\n"
                  + "- 仅当用户明确要求时才使用 " + "emoji\n"
                  + "- 如果 old_string 在文件中不唯一，编辑将失败。此时请提供更长的上下文使 old_string 唯一，或设置 replace_all=true\n"
                  + "- 使用 replace_all=true 可替换文件中所有匹配项\n")
    public String fileEdit(@ToolParam(description = "要编辑的文件绝对路径（必填）") String filePath,
            @ToolParam(description = "要被替换的文本字符串（必填，必须在文件中唯一存在）") String oldString,
            @ToolParam(description = "替换后的新文本字符串（必填，必须与 old_string 不同）") String newString,
            @ToolParam(description = "是否替换所有匹配项，默认 false", required = false) Boolean replaceAll,
            AgentContextHolder contextHolder) {

        if (StringUtils.isBlank(contextHolder.getVfsId())){
            return "编辑失败: 虚拟文件系统未初始化";
        }

        try (VFS vfs = VFS.load(contextHolder.getVfsId())) {
            // 验证 1: 文件存在
            if (!vfs.exists(filePath)) {
                return String.format("编辑失败: 文件不存在 —— %s", filePath);
            }

            // 验证 2: 文件大小
            long fileSize = vfs.size(filePath);
            if (fileSize > MAX_FILE_SIZE) {
                return String.format("编辑失败: 文件过大 (%d bytes)，超过 1 GiB 限制", fileSize);
            }

            // 验证 3: 新旧不能相同
            if (oldString.equals(newString)) {
                return "编辑失败: old_string 和 new_string 相同，没有需要修改的内容";
            }

            // 验证 4: oldString 不能为空
            if (StringUtils.isBlank(oldString)) {
                return "编辑失败: old_string 不能为空";
            }

            // 验证 5: Notebook 文件须使用 NotebookEdit 工具
            if (filePath.toLowerCase().endsWith(".ipynb")) {
                return "编辑失败: 文件是 Jupyter Notebook。请使用 NotebookEdit 工具编辑此文件。";
            }

            // 读取文件原始字节（用于 BOM 检测和编码判断）
            byte[] rawBytes;
            try {
                rawBytes = vfs.readAllBytes(filePath);
            } catch (IOException e) {
                return "编辑失败: 无法读取文件 —— " + e.getMessage();
            }

            // BOM 检测：UTF-16LE (0xFF 0xFE)
            String rawContent;
            if (rawBytes.length >= 2 && (rawBytes[0] & 0xFF) == 0xFF && (rawBytes[1] & 0xFF) == 0xFE) {
                rawContent = new String(rawBytes, 2, rawBytes.length - 2, StandardCharsets.UTF_16LE);
            } else {
                rawContent = new String(rawBytes, StandardCharsets.UTF_8);
            }

            String fileContent = rawContent.replace("\r\n", "\n");
            boolean hasCRLF = rawContent.contains("\r\n");

            // 同样归一化输入
            oldString = oldString.replace("\r\n", "\n");
            newString = newString.replace("\r\n", "\n");

            // 字符串匹配（精确 + 引号规范化）
            String actualOldString = findActualString(fileContent, oldString);
            if (actualOldString == null) {
                return String.format("编辑失败: old_string 在文件中未找到。\n" + "请确认 old_string 与文件内容完全一致（包括空格、缩进和标点符号）。\n"
                        + "old_string: \"%s\"", truncateForDisplay(oldString, 200));
            }

            // 保留文件中的引号风格：当文件使用弯引号而输入使用直引号时，将 newString 的直引号转为弯引号
            newString = preserveQuoteStyle(actualOldString, newString);

            // 唯一性检查
            boolean replaceAllFlag = replaceAll != null && replaceAll;
            int matchCount = countOccurrences(fileContent, actualOldString);
            if (matchCount > 1 && !replaceAllFlag) {
                return String.format("编辑失败: 找到 %d 处匹配，但 replace_all 为 false。\n" + "要替换所有匹配项，请设置 replace_all=true。\n"
                                + "要仅替换其中一处，请提供更多上下文使 old_string 唯一。\n\n" + "匹配的字符串: \"%s\"",
                        matchCount,
                        truncateForDisplay(actualOldString, 200));
            }

            // 执行替换
            String newContent;
            if (replaceAllFlag) {
                newContent = fileContent.replace(actualOldString, newString);
            } else {
                // 只替换第一个
                newContent = fileContent.replaceFirst(Pattern.quote(actualOldString),
                        Matcher.quoteReplacement(newString));
            }

            // 恢复换行符格式
            if (hasCRLF) {
                newContent = newContent.replace("\n", "\r\n");
            }

            // 写回文件（Var_VFS 内部自动处理编码和目录创建）
            vfs.write(filePath, newContent);

            // 生成 diff 预览
            String diffPreview = generateDiffPreview(fileContent, newContent, actualOldString, newString);

            return String.format("✅ 文件编辑成功: %s\n%s 处匹配已替换\n\n%s",
                    filePath,
                    replaceAllFlag ? "所有 " + matchCount : "1",
                    diffPreview);

        } catch (Exception e) {
            log.error("FileEditTool 执行失败", e);
            return "编辑失败: " + e.getMessage();
        }
    }

    /**
     * 查找原始字符串（先精确匹配，再引号规范化匹配）两步匹配逻辑
     *
     * @return 文件中实际匹配的子串，未找到返回 null
     */
    private String findActualString(String fileContent, String searchString) {
        // 1. 精确匹配
        if (fileContent.contains(searchString)) {
            return searchString;
        }

        // 2. 引号规范化匹配：在规范化后的文件中定位位置，再从原始文件中截取实际内容
        String normalizedFile = normalizeQuotes(fileContent);
        String normalizedSearch = normalizeQuotes(searchString);
        int normIdx = normalizedFile.indexOf(normalizedSearch);
        if (normIdx >= 0) {
            // normIdx 对应原始文件中同一位置（规范化不改变字符数），截取原始内容
            return fileContent.substring(normIdx, normIdx + searchString.length());
        }

        return null;
    }

    /**
     * 引号规范化：弯引号 → 直引号
     */
    private String normalizeQuotes(String text) {
        return text.replace('\u2018', '\'')  // ' → '
                .replace('\u2019', '\'')  // ' → '
                .replace('\u201c', '"')   // " → "
                .replace('\u201d', '"')   // " → "
                .replace('\u201e', '"')   // „ → "
                .replace('\u201a', '\''); // ‚ → '
    }

    /**
     * 计算字符串出现次数
     */
    private int countOccurrences(String text, String search) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(search, index)) != -1) {
            count++;
            index += search.length();
        }
        return count;
    }

    /**
     * 生成简洁 diff 预览
     */
    private String generateDiffPreview(String oldContent, String newContent, String oldStr, String newStr) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- 修改预览 ---\n");

        // 找到 oldStr 在原文件中的上下文
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
        if (s == null)
            return "";
        if (s.length() <= maxLen)
            return s;
        return s.substring(0, maxLen) + "...";
    }

    /**
     * 保留文件中的引号风格
     * 当 actualOldString 包含弯引号时，将 newString 中对应的直引号转为弯引号
     */
    private String preserveQuoteStyle(String actualOldString, String newString) {
        boolean hasDoubleCurly = actualOldString.contains("\u201c") || actualOldString.contains("\u201d");
        boolean hasSingleCurly = actualOldString.contains("\u2018") || actualOldString.contains("\u2019");

        if (!hasDoubleCurly && !hasSingleCurly) {
            return newString;
        }

        String result = newString;
        if (hasDoubleCurly) {
            result = applyCurlyQuotes(result, '"', '\u201c', '\u201d');
        }
        if (hasSingleCurly) {
            result = applyCurlyQuotes(result, '\'', '\u2018', '\u2019');
        }
        return result;
    }

    /**
     * 应用弯引号：将字符串中的直引号替换为左右弯引号
     */
    private String applyCurlyQuotes(String str, char straight, char leftCurly, char rightCurly) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == straight) {
                sb.append(isOpeningContext(str, i) ? leftCurly : rightCurly);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 判断引号位置是否为"开头"上下文（空白符/行首/括号等后面）
     */
    private boolean isOpeningContext(String str, int index) {
        if (index == 0)
            return true;
        char prev = str.charAt(index - 1);
        return prev == ' ' || prev == '\t' || prev == '\n' || prev == '\r' || prev == '(' || prev == '[' || prev == '{'
                || prev == '\u2014' || prev == '\u2013';
    }
}
