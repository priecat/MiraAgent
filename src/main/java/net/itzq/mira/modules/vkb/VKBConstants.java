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


    // ==================== 默认值 ====================
    public static final String DEFAULT_DATA_DIR = "./data/vkb";
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.4;
    public static final int DEFAULT_TOP_N = 10;
    public static final int DEFAULT_LUCENE_TOP_N = 100;

    // ==================== 虚拟文件系统路径 ====================
    public static final String VFS_ROOT = "/";
    public static final String VFS_DOCS_DIR = "/docs";
}
