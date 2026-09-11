package net.itzq.mira.modules.vkb.model;

/**
 * 文档模型 - 对应一个源文件
 *
 * @author tangzq
 */
public class Document {

    private String docId;
    private String fileName;
    private String filePath;        // 虚拟文件系统路径
    private String fileExt;
    private long sourceSize;        // 源文件大小
    private long textSize;          // 纯文本大小
    private int chunkCount;         // 分段数量
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
                ", chunkCount=" + chunkCount +
                '}';
    }
}
