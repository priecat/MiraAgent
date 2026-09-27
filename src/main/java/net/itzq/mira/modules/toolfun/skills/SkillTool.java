package net.itzq.mira.modules.toolfun.skills;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.tool.annotation.Tool;
import net.itzq.mira.modules.ai.tool.annotation.ToolParam;
import net.itzq.mira.modules.ai.skills.SkillBundle;
import net.itzq.mira.modules.ai.skills.SkillEntity;
import net.itzq.mira.modules.ai.skills.SkillManager;
import net.itzq.mira.modules.config.GlobalConfigManager;
import net.itzq.mira.modules.toolfun.ToolFun;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 技能工具，让AI可以通过function call调用技能
 * 注册为 @Tool 后，AI可根据用户意图选择合适的技能
 */
@Slf4j
public class SkillTool {

    /** 已知二进制文件扩展名（无法当作文本读取，参考 VfsFileReadTool） */
    private static final Set<String> BINARY_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".exe", ".dll", ".so", ".dylib", ".bin", ".dat", ".class",
            ".jar", ".war", ".ear", ".zip", ".tar", ".gz", ".bz2", ".7z",
            ".o", ".obj", ".lib", ".a", ".pyc", ".pyo",
            ".mp3", ".mp4", ".avi", ".mov", ".wmv", ".flv",
            ".ttf", ".otf", ".woff", ".woff2",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico"
    ));

    @Tool(
            name = ToolFun.TOOL_USE_SKILL,
            display = "使用技能",
            description = "调用指定技能。当用户的任务匹配某个技能的描述或触发词时，调用此工具加载技能的完整指令到上下文。" +
                    "技能提供专业领域知识和操作流程，加载后按照技能指令执行任务。" +
                    "参数skill_name为技能的slug标识，可通过<available_skills>列表查看。"
    )
    public String useSkill(
            @ToolParam(description = "技能slug名称，例如frontend-design，可通过<available_skills>列表查看可用技能") String skill_name,
            AgentContextHolder context
    ) {
        // 校验技能是否在本次可用范围
        List<String> activeSlugs = context.getActiveSkillSlugs();
        if (activeSlugs == null || !activeSlugs.contains(skill_name)) {
            return "技能不在本次可用范围: " + skill_name;
        }

        SkillManager manager = SkillManager.getInstance();
        if (!manager.isInitialized()) {
            return "技能系统未初始化";
        }

        SkillEntity skill = manager.getSkill(skill_name);
        if (skill == null) {
            // 尝试模糊匹配（仅限activeSkillSlugs范围内）
            for (String slug : activeSlugs) {
                if (slug != null && slug.contains(skill_name)) {
                    skill = manager.getSkill(slug);
                    if (skill != null) break;
                }
            }
        }

        if (skill == null) {
            return "技能不存在: " + skill_name + "。可用技能: " + manager.buildSkillsSummary();
        }

        log.info("AI调用技能: {} ({})", skill.getSlug(), skill.getName());

        // 返回技能的完整内容供AI加载到上下文
        StringBuilder result = new StringBuilder();
        result.append("<skill_content slug=\"").append(skill.getSlug()).append("\">\n");
        result.append(skill.getContent());
        result.append("\n</skill_content>");
        result.append("\n\n如需读取技能内的脚本或文档，使用 read_skill_file 工具，");
        result.append("参数 skill_name=").append(skill.getSlug());
        result.append("，file_path 为技能包内相对路径（如 scripts/build.py）。");
        result.append("\n注意：二进制文件（图片/字体/Office 文档等）无法直接读取，");
        result.append("可用 expand_skill 工具把技能包展开到磁盘目录后处理。");

        return result.toString();
    }

    @Tool(
            name = ToolFun.TOOL_READ_SKILL_FILE,
            display = "读取技能文件",
            description = "从技能包(zip)中读取指定文件。用于读取技能内的脚本、参考文档等资源文件。" +
                    "skill_name为技能slug，file_path为技能包内相对路径。"
    )
    public String readSkillFile(
            @ToolParam(description = "技能slug，如 pptx-generator, minimax-pdf") String skill_name,
            @ToolParam(description = "技能包内文件相对路径，如 scripts/build.py, references/api.md") String file_path,
            AgentContextHolder context
    ) {
        // 校验技能是否在本次可用范围
        List<String> activeSlugs = context.getActiveSkillSlugs();
        if (activeSlugs == null || !activeSlugs.contains(skill_name)) {
            return "技能不在本次可用范围: " + skill_name;
        }

        try {
            SkillBundle bundle = SkillBundle.get(skill_name);
            if (!bundle.exists(file_path)) {
                return "文件不存在: " + skill_name + "/" + file_path;
            }
            // 二进制文件无法当作文本读取（参考 VfsFileReadTool），提示展开技能包到磁盘
            String fileName = file_path.substring(file_path.lastIndexOf('/') + 1);
            String ext = fileName.contains(".")
                    ? fileName.substring(fileName.lastIndexOf('.')).toLowerCase() : "";
            if (isBinaryFile(fileName)) {
                return String.format(
                        "无法读取二进制文件: %s/%s (扩展名: %s)。"
                                + "请调用 expand_skill 工具把技能包展开到磁盘目录（%s），"
                                + "再用文件工具处理该文件。",
                        skill_name, file_path, ext.isEmpty() ? "未知" : ext,
                        ToolFun.TOOL_EXPAND_SKILL);
            }
            return bundle.readFile(file_path);
        } catch (UncheckedIOException e) {
            return "读取技能文件失败: " + e.getMessage();
        } catch (Exception e) {
            return "读取技能文件异常: " + e.getMessage();
        }
    }

    @Tool(
            name = ToolFun.TOOL_EXPAND_SKILL,
            display = "展开技能包",
            description = "把技能包(zip)完整解压到磁盘目录 <dataDir>/skills-expand/<skill_name>/。"
                    + "当 read_skill_file 提示文件是二进制（图片/字体/Office文档/编译产物等）无法直接读取时使用；"
                    + "展开后可直接对目录内文件执行脚本、编译、分析等操作。返回展开后的绝对路径。"
    )
    public String expandSkill(
            @ToolParam(description = "技能slug，可通过<available_skills>列表查看可用技能") String skill_name,
            AgentContextHolder context
    ) {
        // 校验技能是否在本次可用范围
        List<String> activeSlugs = context.getActiveSkillSlugs();
        if (activeSlugs == null || !activeSlugs.contains(skill_name)) {
            return "技能不在本次可用范围: " + skill_name;
        }

        String dataDir = GlobalConfigManager.config().getWorkspaceConfig().getDataDir();
        if (dataDir == null || dataDir.trim().isEmpty()) {
            return "无法展开技能包: GlobalConfigManager.config().getWorkspaceConfig().getDataDir() 未配置";
        }

        try {
            SkillBundle bundle = SkillBundle.get(skill_name);
            Path target = Paths.get(dataDir, "skills-expand", skill_name).normalize();
            int count = bundle.expandTo(target);
            log.info("技能包已展开: {} -> {} ({} 个文件)", skill_name, target.toAbsolutePath(), count);
            return "技能包已展开: " + skill_name
                    + "\n目标目录: " + target.toAbsolutePath()
                    + "\n共解压 " + count + " 个文件。"
                    + "脚本/二进制等资源请直接在该目录下操作（执行脚本时注意先确认可执行权限）。";
        } catch (UncheckedIOException e) {
            return "展开技能包失败: " + e.getMessage();
        } catch (Exception e) {
            return "展开技能包异常: " + e.getMessage();
        }
    }

    /** 检测是否为已知二进制文件（不可当文本读取） */
    private boolean isBinaryFile(String fileName) {
        String lowerName = fileName.toLowerCase();
        for (String ext : BINARY_EXTENSIONS) {
            if (lowerName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}
