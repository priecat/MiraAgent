package net.itzq.mira.modules.workspace;

/**
 * Workspace 常量定义
 *
 * @author tangzq
 */
public class WorkspaceConstants {

    // ==================== 上下文变量名 ====================
    /** Workspace 实例在 AgentContextHolder.tempVariables 中的键 */
    public static final String VAR_SESSION_WORKSPACE = "workspace_session";

    // ==================== 默认值 ====================
    public static final int DEFAULT_TOP_N = 10;
    public static final int DEFAULT_LUCENE_TOP_N = 100;

    // ==================== 文件系统路径 ====================
    public static final String FS_ROOT = "/";
    public static final String FS_DOCS_DIR = "/docs";
}
