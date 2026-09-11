package net.itzq.mira.modules.vkb.model;

/**
 * 知识库统计信息
 *
 * @author tangzq
 */
public class KBInfo {

    private String sessionId;
    private int totalDocuments;
    private int totalChunks;
    private long sourceTotalSize;
    private long textTotalSize;
    private long createdAt;

    public KBInfo() {
    }

    public KBInfo(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = System.currentTimeMillis();
    }

    // ==================== Getter/Setter ====================

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public int getTotalDocuments() {
        return totalDocuments;
    }

    public void setTotalDocuments(int totalDocuments) {
        this.totalDocuments = totalDocuments;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    public long getSourceTotalSize() {
        return sourceTotalSize;
    }

    public void setSourceTotalSize(long sourceTotalSize) {
        this.sourceTotalSize = sourceTotalSize;
    }

    public long getTextTotalSize() {
        return textTotalSize;
    }

    public void setTextTotalSize(long textTotalSize) {
        this.textTotalSize = textTotalSize;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "KBInfo{" +
                "sessionId='" + sessionId + '\'' +
                ", totalDocuments=" + totalDocuments +
                ", totalChunks=" + totalChunks +
                ", sourceTotalSize=" + sourceTotalSize +
                ", textTotalSize=" + textTotalSize +
                '}';
    }
}
