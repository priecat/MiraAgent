package net.itzq.mira.modules.vfs.model;

/**
 * 目录模型 - 对应磁盘上的一个真实文件夹
 *
 * <p>代表工作空间文件系统中的一个文件夹，支持网盘式的目录操作。
 *
 * @author tangzq
 */
public class Directory  {

    private String dirId;
    private String dirPath;         // 完整路径，如 /docs/reports/
    private String dirName;         // 目录名，如 reports
    private String parentPath;      // 父目录路径，如 /docs/
    private String realPath;        // 磁盘真实路径
    private String mappedPath;      // 映射路径（mapDir + 虚拟路径；未配置时等于真实路径）
    private long createdAt;
    private long updatedAt;

    public Directory() {
    }

    public Directory(String dirId, String dirPath, String dirName, String parentPath) {
        this.dirId = dirId;
        this.dirPath = dirPath;
        this.dirName = dirName;
        this.parentPath = parentPath;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    // ==================== Getter/Setter ====================

    public String getDirId() {
        return dirId;
    }

    public void setDirId(String dirId) {
        this.dirId = dirId;
    }

    public String getDirPath() {
        return dirPath;
    }

    public void setDirPath(String dirPath) {
        this.dirPath = dirPath;
    }

    public String getDirName() {
        return dirName;
    }

    public void setDirName(String dirName) {
        this.dirName = dirName;
    }

    public String getParentPath() {
        return parentPath;
    }

    public void setParentPath(String parentPath) {
        this.parentPath = parentPath;
    }

    /**
     * 获取磁盘真实路径（真实路径概念）。
     */
    public String getRealPath() {
        return realPath;
    }

    public void setRealPath(String realPath) {
        this.realPath = realPath;
    }

    /**
     * 获取映射路径（mapDir + 虚拟路径；mapDir 未配置时等于真实路径）。
     */
    public String getMappedPath() {
        return mappedPath;
    }

    public void setMappedPath(String mappedPath) {
        this.mappedPath = mappedPath;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "Directory{" +
                "dirPath='" + dirPath + '\'' +
                ", dirName='" + dirName + '\'' +
                ", parentPath='" + parentPath + '\'' +
                ", realPath='" + realPath + '\'' +
                ", mappedPath='" + mappedPath + '\'' +
                '}';
    }
}
