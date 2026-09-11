package net.itzq.mira.modules.toolfun.vkb;

import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.tool.annotation.Tool;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
import net.itzq.mira.modules.toolfun.ToolFun;
import net.itzq.mira.modules.vkb.SessionKB;
import net.itzq.mira.modules.vkb.VKBConstants;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * VKB Glob 工具 - 文件模式匹配
 *
 * @author tangzq
 */
public class VkbGlobTool {

    private static final Logger log = LoggerFactory.getLogger(VkbGlobTool.class);
    private static final int DEFAULT_MAX_RESULTS = 100;

    @Tool(name = ToolFun.TOOL_VKB_GLOB,
          description = "知识库文件模式匹配工具，通过 glob 模式查找文件名。\n"
                  + "示例：\n"
                  + "  - 查找所有 .md 文件: pattern=\"**/*.md\"\n"
                  + "  - 在 /docs 下找所有文件: pattern=\"**/*\", path=\"/docs\"\n"
                  + "返回匹配的文件路径（最多 100 个）。")
    public String glob(
            @ToolParam(description = "glob 模式（必填），例如 \"**/*.md\"") String pattern,
            @ToolParam(description = "从哪个目录开始搜索（可选），默认根目录 '/'", required = false) String path,
            AgentContextHolder contextHolder) {

        try {
            SessionKB kb = getSessionKB(contextHolder);
            if (kb == null) {
                return "错误: 知识库未初始化";
            }

            FileSystem fs = kb.getFileSystem();
            String searchPath = StringUtils.isBlank(path) ? "/" : path;
            Path rootPath = fs.getPath(searchPath);

            if (!Files.exists(rootPath)) {
                return "错误：搜索目录不存在: " + searchPath;
            }

            // 创建 PathMatcher
            final String patternStr = pattern.replace('\\', '/');
            PathMatcher matcher = fs.getPathMatcher("glob:" + patternStr);

            List<String> entries = new ArrayList<>();
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Path relative = rootPath.relativize(file);
                        String relativeStr = relative.toString().replace('\\', '/');
                        if (matcher.matches(file.getFileName()) ||
                                matcher.matches(fs.getPath(relativeStr))) {
                            entries.add(file.toString());
                        }
                    } catch (Exception ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            int total = entries.size();
            boolean truncated = total > DEFAULT_MAX_RESULTS;

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("在 \"%s\" 下匹配 glob \"%s\" 的结果:", searchPath, patternStr));
            sb.append(String.format(" 共 %d 个文件", total));
            if (truncated) {
                sb.append(String.format("，仅显示前 %d 个", DEFAULT_MAX_RESULTS));
            }
            sb.append("\n");

            if (total == 0) {
                sb.append("（无匹配文件）\n");
            } else {
                int shown = Math.min(total, DEFAULT_MAX_RESULTS);
                for (int i = 0; i < shown; i++) {
                    sb.append(entries.get(i)).append("\n");
                }
                if (truncated) {
                    sb.append(String.format("\n[结果已截断: 显示 %d/%d 个文件]", DEFAULT_MAX_RESULTS, total));
                }
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("VKB Glob 失败", e);
            return "文件匹配发生异常: " + e.getMessage();
        }
    }

    private SessionKB getSessionKB(AgentContextHolder contextHolder) {
        Object kbObj = contextHolder.getTopTempVariables().get(VKBConstants.VAR_SESSION_KB);
        if (kbObj instanceof SessionKB) {
            return (SessionKB) kbObj;
        }
        return null;
    }
}
