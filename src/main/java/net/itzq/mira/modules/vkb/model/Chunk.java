package net.itzq.mira.modules.vkb.model;

/**
 * 分段模型 - 文档的一个切片
 *
 * @author tangzq
 */
public class Chunk {

    private String chunkId;
    private String docId;
    private int chunkIndex;         // 分段序号（从0开始）
    private String content;         // 分段文本内容
    private int startOffset;        // 在原文中的起始位置
    private int endOffset;          // 在原文中的结束位置
    private long createdAt;

    public Chunk() {
    }

    public Chunk(String chunkId, String docId, int chunkIndex, String content) {
        this.chunkId = chunkId;
        this.docId = docId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.createdAt = System.currentTimeMillis();
    }

    // ==================== Getter/Setter ====================

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public void setStartOffset(int startOffset) {
        this.startOffset = startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }

    public void setEndOffset(int endOffset) {
        this.endOffset = endOffset;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "Chunk{" +
                "chunkId='" + chunkId + '\'' +
                ", docId='" + docId + '\'' +
                ", chunkIndex=" + chunkIndex +
                ", contentLength=" + (content != null ? content.length() : 0) +
                '}';
    }
}
