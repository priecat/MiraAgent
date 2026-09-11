package net.itzq.mira.modules.vkb;

import net.itzq.mira.modules.vkb.fs.SQLiteFileSystem;
import net.itzq.mira.modules.vkb.fs.SQLiteFileSystemProvider;
import net.itzq.mira.modules.vkb.model.*;
import net.itzq.mira.modules.vkb.provider.EmbeddingProvider;
import net.itzq.mira.modules.vkb.storage.LuceneStorage;
import net.itzq.mira.modules.vkb.storage.SQLiteStorage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 会话虚拟工作空间实例
 *
 * 每个实例对应一个 SQLite 数据库 + 一个 Lucene 索引目录 + 一个虚拟文件系统
 *
 * @author tangzq
 */
public class SessionKB implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SessionKB.class);

    private final String sessionId;
    private final VKBConfig config;
    private final String dbPath;
    private final String lucenePath;
    private final SQLiteStorage sqliteStorage;
    private final LuceneStorage luceneStorage;
    private final SQLiteFileSystem fileSystem;
    private final EmbeddingProvider embeddingProvider;

    public SessionKB(String sessionId, VKBConfig config) throws Exception {
        this.sessionId = sessionId;
        this.config = config;

        // 计算路径: data/ab/cd/[uuid]/
        String dir = buildSessionDir(sessionId);
        new File(dir).mkdirs();

        this.dbPath = dir + File.separator + sessionId + ".db";
        this.lucenePath = dir + File.separator + sessionId + "-lucene";

        // 初始化存储层
        this.sqliteStorage = new SQLiteStorage(dbPath, config);
        this.luceneStorage = new LuceneStorage(lucenePath);
        this.embeddingProvider = null;

        // 初始化文件系统
        SQLiteFileSystemProvider provider = new SQLiteFileSystemProvider();
        this.fileSystem = provider.registerFileSystem(sessionId, sqliteStorage);

        log.info("SessionKB 初始化完成: sessionId={}, dbPath={}", sessionId, dbPath);
    }

    /**
     * 构建会话目录路径: data/ab/cd/[uuid]/
     */
    private String buildSessionDir(String sessionId) {
        String a = sessionId.substring(0, 2);
        String b = sessionId.substring(2, 4);
        return config.getDataDir() + File.separator + a + File.separator + b + File.separator + sessionId;
    }

    // ==================== 文档操作 ====================

    /**
     * 添加文档（支持分段）
     *
     * @param fileName      原始文件名（如 report.pdf）
     * @param sourceBytes   源文件字节（可为 null）
     * @param filePath      虚拟文件系统路径（如 /docs/report.md）
     * @param textChunks    分段后的文本列表
     * @return 文档ID
     */
    public String addDocument(String fileName, byte[] sourceBytes, String filePath, List<String> textChunks) {
        String docId = generateId();
        long now = System.currentTimeMillis();

        if (textChunks == null) {
            textChunks = Collections.singletonList("");
        }

        // 计算文本总大小
        long textSize = textChunks.stream().mapToLong(String::length).sum();

        if (StringUtils.isBlank(fileName)) {
            fileName = "未命名-" + docId + ".txt";
        }

        // 1. 存储文档元数据
        String fullPath;
        if (StringUtils.endsWith(filePath, "/")) {
            fullPath = filePath + fileName;
        } else {
            fullPath = filePath + "/" + fileName;
        }

        String fullText = String.join("", textChunks);

        if (StringUtils.isBlank(fullText)) {
            fullText = "";
        }

        if (sourceBytes == null) {
            sourceBytes = fullText.getBytes(StandardCharsets.UTF_8);
        }

        Document doc = new Document(docId, fileName, fullPath);
        doc.setFileExt(getFileExtension(fileName));
        doc.setSourceSize(sourceBytes.length);
        doc.setTextSize(textSize);
        doc.setChunkCount(textChunks.size());
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        sqliteStorage.insertDocument(doc);

        // 2. 存储源文件
        sqliteStorage.storeSourceFile(docId, sourceBytes);

        // 3. 存储完整知识文本（拼接所有分段）
        sqliteStorage.storeKnowledgeText(docId, fullText);

        // 4. 存储分段并生成向量
        int offset = 0;
        for (int i = 0; i < textChunks.size(); i++) {
            String chunkId = generateId();
            String chunkContent = textChunks.get(i);

            // 存储分段
            Chunk chunk = new Chunk(chunkId, docId, i, chunkContent);
            chunk.setStartOffset(offset);
            chunk.setEndOffset(offset + chunkContent.length());
            sqliteStorage.storeChunk(chunk);

            // 向量化并存储
            //            if (embeddingProvider != null && config.isVecEnabled()) {
            //                try {
            //                    float[] embedding = embeddingProvider.embed(chunkContent);
            //                    if (embedding != null) {
            //                        sqliteStorage.storeVector(chunkId, embedding);
            //                    }
            //                } catch (Exception e) {
            //                    log.warn("分段向量化失败: chunkId={}, error={}", chunkId, e.getMessage());
            //                }
            //            }

            offset += chunkContent.length();
        }

        // 5. Lucene 索引（索引完整文本）
        if (luceneStorage != null && luceneStorage.isInitialized()) {
            luceneStorage.indexDocument(docId, filePath, fullText);
        }

        log.info("文档添加成功: docId={}, fileName={}, chunks={}", docId, fileName, textChunks.size());
        return docId;
    }

    // ==================== 搜索操作 ====================

    /**
     * 搜索：返回相关文件列表（不是片段）
     *
     * 第一阶段：向量搜索 + Lucene 搜索找到相关分段
     * 第二阶段：聚合到文件级别，返回文件路径列表
     *
     * @param query 查询关键词
     * @return 相关文件列表
     */
    public List<SearchResult> search(String query, int resultSize) {

        List<ChunkMatch> chunkMatches = new ArrayList<>();

        // Lucene 全文检索
        if ( luceneStorage != null && luceneStorage.isInitialized()) {
            List<SearchResult> luceneResults = luceneStorage.search(query, config.getLuceneTopN());
            for (SearchResult r : luceneResults) {
                chunkMatches.add(new ChunkMatch(r.getDocId(), 0, r.getScore(), "lucene"));
            }
            log.debug("Lucene 搜索返回 {} 个结果", luceneResults.size());
        }

        // sqlite-vec 向量检索
        //        if (config.isHybridVecEnabled() && embeddingProvider != null && config.isVecEnabled()) {
        //            try {
        //                int vectorCount = sqliteStorage.getVectorCount();
        //                log.info("开始向量搜索: query={}, 向量表中有 {} 条记录", query, vectorCount);
        //
        //                if (vectorCount == 0) {
        //                    log.warn("向量表为空，跳过向量搜索");
        //                } else {
        //                    float[] queryVec = embeddingProvider.embed(query);
        //                    if (queryVec != null) {
        //                        log.info("查询向量生成成功: dim={}", queryVec.length);
        //                        List<SQLiteStorage.VectorMatch> vecResults =
        //                                sqliteStorage.searchSimilarVectors(queryVec, config.getVecTopN());
        //
        //                        log.info("向量搜索原始结果: {} 个, 阈值={}", vecResults.size(), config.getSimilarityThreshold());
        //                        for (SQLiteStorage.VectorMatch match : vecResults) {
        //                            log.info("  向量匹配: docId={}, chunkIndex={}, similarity={}",
        //                                    match.getDocId(), match.getChunkIndex(), match.getScore());
        //                            if (match.getScore() >= config.getSimilarityThreshold()) {
        //                                chunkMatches.add(new ChunkMatch(
        //                                        match.getDocId(), match.getChunkIndex(),
        //                                        match.getScore(), "vector"));
        //                            }
        //                        }
        //                        log.info("向量搜索通过阈值: {} 个结果", chunkMatches.size());
        //                    } else {
        //                        log.warn("查询向量生成失败: embed() 返回 null");
        //                    }
        //                }
        //            } catch (Exception e) {
        //                log.error("向量搜索失败: {}", e.getMessage(), e);
        //            }
        //        } else {
        //            log.info("向量搜索跳过: hybridVecEnabled={}, embeddingProvider={}, vecEnabled={}",
        //                    config.isHybridVecEnabled(), embeddingProvider != null, config.isVecEnabled());
        //        }

        // 第二阶段：聚合到文件级别
        Map<String, SearchResult> fileResults = new LinkedHashMap<>();
        for (ChunkMatch match : chunkMatches) {
            String docId = match.docId;
            if (docId == null)
                continue;

            fileResults.computeIfAbsent(docId, id -> {
                Document doc = sqliteStorage.getDocument(id);
                if (doc == null)
                    return null;
                return new SearchResult(doc.getDocId(), doc.getFileName(), doc.getFilePath(), 0.0, match.source);
            });

            SearchResult result = fileResults.get(docId);
            if (result != null) {
                // 更新最高分数
                if (match.score > result.getScore()) {
                    result.setScore(match.score);
                }
                // 记录匹配的分段
                result.addMatchedChunk(new SearchResult.ChunkMatch(null,
                        match.chunkIndex,
                        match.score,
                        "分段 " + match.chunkIndex));
            }
        }

        // 按最高相似度排序
        List<SearchResult> results = fileResults.values()
                .stream()
                .filter(Objects::nonNull)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());

        // 限制数量
        if (results.size() > resultSize) {
            results = results.subList(0, resultSize);
        }

        log.info("搜索完成: query={}, 结果数={}", query, results.size());
        return results;
    }

    /**
     * Grep 搜索（正则表达式）
     *
     * @param regex 正则表达式
     * @return 匹配结果
     */
    public List<SearchResult> grep(String regex, int resultSize) {
        List<SearchResult> results = new ArrayList<>();

        List<SQLiteStorage.GrepResult> grepResults = sqliteStorage.grepKnowledgeTexts(regex, resultSize);

        for (SQLiteStorage.GrepResult grepResult : grepResults) {
            SearchResult result = new SearchResult(grepResult.getDocId(),
                    grepResult.getFileName(),
                    grepResult.getFilePath(),
                    1.0,
                    "grep");
            results.add(result);
        }

        return results;
    }

    // ==================== 文件操作 ====================

    /**
     * 获取源文件
     */
    public byte[] getSourceFile(String docId) {
        return sqliteStorage.getSourceFile(docId);
    }

    /**
     * 获取源文件（通过路径）
     */
    public byte[] getSourceFileByPath(String filePath) {
        Document doc = sqliteStorage.getDocumentByPath(filePath);
        if (doc != null) {
            return sqliteStorage.getSourceFile(doc.getDocId());
        }
        return null;
    }

    /**
     * 获取知识文本
     */
    public String getKnowledgeText(String docId) {
        return sqliteStorage.getKnowledgeText(docId);
    }

    /**
     * 获取知识文本（通过路径）
     */
    public String getKnowledgeTextByPath(String filePath) {
        Document doc = sqliteStorage.getDocumentByPath(filePath);
        if (doc != null) {
            return sqliteStorage.getKnowledgeText(doc.getDocId());
        }
        return null;
    }

    /**
     * 删除文档
     */
    public void deleteDocument(String docId) {
        sqliteStorage.deleteDocument(docId);
        if (luceneStorage != null) {
            luceneStorage.deleteDocument(docId);
        }
        log.info("文档已删除: docId={}", docId);
    }

    /**
     * 列出所有文档
     */
    public List<Document> listDocuments() {
        return sqliteStorage.listDocuments();
    }

    // ==================== 向量表管理 ====================

    /**
     * 重建向量表（当维度变化时调用）
     *
     * @param newDimension 新的向量维度
     */
    public void recreateVectorTable(int newDimension) {
        sqliteStorage.recreateVectorTable(newDimension);
    }

    /**
     * 获取当前向量表的维度
     *
     * @return 维度数，-1 表示表不存在
     */
    public int getCurrentVectorDimension() {
        return sqliteStorage.getCurrentVectorDimension();
    }

    // ==================== 文件系统 ====================

    /**
     * 获取虚拟文件系统
     */
    public FileSystem getFileSystem() {
        return fileSystem;
    }

    /**
     * 获取虚拟工作空间统计信息
     */
    public KBInfo getInfo() {
        return sqliteStorage.getInfo(sessionId);
    }

    public SQLiteStorage getSqliteStorage() {
        return sqliteStorage;
    }
    // ==================== 生命周期 ====================

    @Override
    public void close() {
        if (sqliteStorage != null)
            sqliteStorage.close();
        if (luceneStorage != null)
            luceneStorage.close();
        if (fileSystem != null) {
            try {
                fileSystem.close();
            } catch (Exception ignored) {
            }
        }
        log.info("SessionKB 已关闭: sessionId={}", sessionId);
    }

    // ==================== 工具方法 ====================

    private String generateId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1) : "";
    }

    private String getFileNameFromPath(String filePath) {
        int lastSlash = filePath.lastIndexOf('/');
        return lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
    }

    // ==================== 内部类 ====================

    private static class ChunkMatch {
        final String docId;
        final int chunkIndex;
        final double score;
        final String source;

        ChunkMatch(String docId, int chunkIndex, double score, String source) {
            this.docId = docId;
            this.chunkIndex = chunkIndex;
            this.score = score;
            this.source = source;
        }
    }
}
