package net.itzq.mira.modules.vkb.model;

/**
 * 目录模型 - 对应 directories 表中的一条记录
 * <p>
 * 代表虚拟文件系统中的一个文件夹，支持网盘式的目录操作。
 *
 * @author tangzq
 */
public class Directory {

    private String dirId;
    private String dirPath;         // 完整路径，如 /docs/reports/
    private String dirName;         // 目录名，如 reports
    private String parentPath;      // 父目录路径，如 /docs/
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
                '}';
    }
}
