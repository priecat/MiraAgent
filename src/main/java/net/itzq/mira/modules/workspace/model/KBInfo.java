package net.itzq.mira.modules.workspace.model;

/**
 * 工作空间统计信息
 *
 * @author tangzq
 */
public class KBInfo {

    private String sessionId;
    private int totalDocuments;
    private int totalChunks;
    private int totalDirectories;
    private long sourceTotalSize;
    private long textTotalSize;
    private long createdAt;
    private boolean initialized;

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

    public int getTotalDirectories() {
        return totalDirectories;
    }

    public void setTotalDirectories(int totalDirectories) {
        this.totalDirectories = totalDirectories;
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

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    @Override
    public String toString() {
        return "KBInfo{" +
                "sessionId='" + sessionId + '\'' +
                ", initialized=" + initialized +
                ", totalDocuments=" + totalDocuments +
                ", totalDirectories=" + totalDirectories +
                ", totalChunks=" + totalChunks +
                ", sourceTotalSize=" + sourceTotalSize +
                ", textTotalSize=" + textTotalSize +
                '}';
    }
}
