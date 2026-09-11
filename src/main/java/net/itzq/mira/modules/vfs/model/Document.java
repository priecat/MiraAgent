package net.itzq.mira.modules.vfs.model;

/**
 * 文档模型 - 对应磁盘上的一个真实文件
 *
 * <p>在文件存储实现中，docId 直接使用文件的虚拟路径（如 /docs/report.md），
 * 保证唯一且可确定性推导，无需额外的元数据表。
 *
 * @author tangzq
 */
public class Document  {

    private String docId;
    private String fileName;
    private String filePath;        // 虚拟文件系统路径（Storage 虚拟路径）
    private String realPath;        // 磁盘真实路径
    private String mappedPath;      // 映射路径（mapDir + 虚拟路径；未配置时等于真实路径）
    private String fileExt;
    private long sourceSize;        // 源文件大小（磁盘字节数）
    private long textSize;          // 纯文本大小
    private int chunkCount;         // 分段数量（文件存储下默认为 1）
    private long createdAt;
    private long updatedAt;

    public Document() {
    }

    public Document(String docId, String fileName, String filePath) {
        this.docId = docId;
        this.fileName = fileName;
        this.filePath = filePath;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    // ==================== Getter/Setter ====================

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
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

    public String getFileExt() {
        return fileExt;
    }

    public void setFileExt(String fileExt) {
        this.fileExt = fileExt;
    }

    public long getSourceSize() {
        return sourceSize;
    }

    public void setSourceSize(long sourceSize) {
        this.sourceSize = sourceSize;
    }

    public long getTextSize() {
        return textSize;
    }

    public void setTextSize(long textSize) {
        this.textSize = textSize;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
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
        return "Document{" +
                "docId='" + docId + '\'' +
                ", fileName='" + fileName + '\'' +
                ", filePath='" + filePath + '\'' +
                ", realPath='" + realPath + '\'' +
                ", mappedPath='" + mappedPath + '\'' +
                ", chunkCount=" + chunkCount +
                '}';
    }
}
