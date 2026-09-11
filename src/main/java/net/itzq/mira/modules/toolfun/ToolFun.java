package net.itzq.mira.modules.toolfun;

import net.itzq.mira.modules.toolfun.code.*;
import net.itzq.mira.modules.toolfun.explorer.*;
import net.itzq.mira.modules.toolfun.mcp.McpTool;
import net.itzq.mira.modules.toolfun.skills.SkillTool;
import net.itzq.mira.modules.toolfun.vfs.*;
import net.itzq.mira.modules.toolfun.vkb.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 *
 * @discription
 *
 * @created 2026/7/22 21:59
 */
public class ToolFun {

    // ==================== vkb 工具名 ====================
    public static final String TOOL_VKB_SEARCH = "vkb_search";
    public static final String TOOL_VKB_GREP = "vkb_grep";
    public static final String TOOL_VKB_FILE_READ = "vkb_file_read";
    public static final String TOOL_VKB_FILE_WRITE = "vkb_file_write";
    public static final String TOOL_VKB_FILE_EDIT = "vkb_file_edit";
    public static final String TOOL_VKB_GLOB = "vkb_glob";

    // ==================== vfs 工具名 ====================
    public static final String Tool_VFS_Glob = "vfs_glob";
    public static final String Tool_VFS_Grep = "vfs_grep";
    public static final String Tool_VFS_File_Edit = "vfs_file_edit";
    public static final String Tool_VFS_File_Read = "vfs_file_read";
    public static final String Tool_VFS_File_Write = "vfs_file_write";

    // ==================== skill 工具名 ====================
    public static final String TOOL_USE_SKILL = "use_skill";

    // ==================== skill 工具名 ====================
    public static final String TOOL_MCP_CALL = "mcp_call";

    // ==================== code 工具名 ====================
    public static final String TOOL_Bash = "Bash";
    public static final String TOOL_Edit = "Edit";
    public static final String TOOL_Read = "Read";
    public static final String TOOL_Write = "Write";
    public static final String TOOL_Glob = "Glob";
    public static final String TOOL_Grep = "Grep";

    // ==================== explorer 工具名（code-explorer子代理专用） ====================
    public static final String TOOL_CODE_EXPLORER = "code_explorer";
    public static final String TOOL_LIST_FILES = "list_files";


    public static Class<?>[] defaultToolFun() {
        Class<?>[] arr = {
                //  VKB
                VkbFileEditTool.class, VkbFileWriteTool.class, VkbFileReadTool.class, VkbGlobTool.class,
                VkbSearchTool.class, VkbGrepTool.class,
                // VFS
                VfsGrepTool.class, VfsGlobTool.class, VfsFileWriteTool.class, VfsFileReadTool.class,
                VfsFileEditTool.class ,
                // skills
                SkillTool.class,
                // mcp
                McpTool.class,
                // code
                BashTool.class, FileEditTool.class, FileReadTool.class, FileWriteTool.class, GlobTool.class,
                GrepTool.class,
                // explorer（code-explorer子代理工具 + 主代理入口工具）
                CodeExplorerTool.class, ListFilesTool.class,

        };
        return arr;
    }
}
