package net.itzq.mira.modules.vkb;

/**
 * VKB 常量定义
 *
 * @author tangzq
 */
public class VKBConstants {

    // ==================== 上下文变量名 ====================
    /** VKB SessionKB 实例在 AgentContextHolder.tempVariables 中的键 */
    public static final String VAR_SESSION_KB = "vkb_session";

    // ==================== Agent 工具名 ====================
    public static final String TOOL_SEARCH = "vkb_search";
    public static final String TOOL_GREP = "vkb_grep";
    public static final String TOOL_FILE_READ = "vkb_file_read";
    public static final String TOOL_FILE_WRITE = "vkb_file_write";
    public static final String TOOL_FILE_EDIT = "vkb_file_edit";
    public static final String TOOL_GLOB = "vkb_glob";

    // ==================== 默认值 ====================
    public static final String DEFAULT_DATA_DIR = "./data/vkb";
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.4;
    public static final int DEFAULT_TOP_N = 10;
    public static final int DEFAULT_LUCENE_TOP_N = 100;

    // ==================== 虚拟文件系统路径 ====================
    public static final String VFS_ROOT = "/";
    public static final String VFS_DOCS_DIR = "/docs";
}
