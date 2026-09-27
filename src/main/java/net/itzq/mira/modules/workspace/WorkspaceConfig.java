package net.itzq.mira.modules.workspace;

import lombok.Data;

/**
 * Workspace 配置类
 *
 * @author tangzq
 */
@Data
public class WorkspaceConfig {

    /** 数据根目录 */
    private String dataDir = null;

    /**
     * 映射根目录（虚拟路径风格，如 /workspace）。
     * 用于把"真实磁盘路径"映射为一个外部可识别的逻辑路径：
     *   虚拟路径 /data/hello.txt
     *   真实路径 <storageDir>/data/hello.txt
     *   映射路径 <mapDir>/data/hello.txt  （如 /workspace/data/hello.txt）
     * 为 null 或空时，映射路径 == 真实路径。
     */
    private String mapDir;

}
