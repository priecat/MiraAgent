package net.itzq.mira.modules.vfs.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索结果 - 文件级别
 *
 * @author tangzq
 */
public class SearchResult  {

    private String docId;
    private String fileName;
    private String filePath;           // 工作空间文件系统路径（Storage 虚拟路径）
    private String realPath;           // 磁盘真实路径
    private String mappedPath;         // 映射路径（mapDir + 虚拟路径；未配置时等于真实路径）
    private double score;              // 最高相似度
    private String source;             // 来源: "vector", "lucene", "grep"
    private List<ChunkMatch> matchedChunks;  // 匹配的分段列表

    public SearchResult() {
        this.matchedChunks = new ArrayList<>();
    }

    public SearchResult(String docId, String fileName, String filePath, double score, String source) {
        this.docId = docId;
        this.fileName = fileName;
        this.filePath = filePath;
        this.score = score;
        this.source = source;
        this.matchedChunks = new ArrayList<>();
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

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public List<ChunkMatch> getMatchedChunks() {
        return matchedChunks;
    }

    public void setMatchedChunks(List<ChunkMatch> matchedChunks) {
        this.matchedChunks = matchedChunks;
    }

    public void addMatchedChunk(ChunkMatch match) {
        this.matchedChunks.add(match);
    }

    @Override
    public String toString() {
        return "SearchResult{" +
                "filePath='" + filePath + '\'' +
                ", realPath='" + realPath + '\'' +
                ", mappedPath='" + mappedPath + '\'' +
                ", score=" + score +
                ", source='" + source + '\'' +
                ", matchedChunks=" + matchedChunks.size() +
                '}';
    }

    /**
     * 分段匹配信息
     */
    public static class ChunkMatch {
        private String chunkId;
        private int chunkIndex;
        private double score;
        private String contentPreview;  // 内容预览

        public ChunkMatch() {
        }

        public ChunkMatch(String chunkId, int chunkIndex, double score, String contentPreview) {
            this.chunkId = chunkId;
            this.chunkIndex = chunkIndex;
            this.score = score;
            this.contentPreview = contentPreview;
        }

        public String getChunkId() {
            return chunkId;
        }

        public void setChunkId(String chunkId) {
            this.chunkId = chunkId;
        }

        public int getChunkIndex() {
            return chunkIndex;
        }

        public void setChunkIndex(int chunkIndex) {
            this.chunkIndex = chunkIndex;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public String getContentPreview() {
            return contentPreview;
        }

        public void setContentPreview(String contentPreview) {
            this.contentPreview = contentPreview;
        }
    }
}
