package net.itzq.mira.modules.workspace;

import net.itzq.mira.modules.workspace.model.*;
import net.itzq.mira.modules.workspace.storage.FileStorage;
import net.itzq.mira.modules.workspace.storage.LuceneStorage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 会话工作空间实例（磁盘文件存储版）
 *
 * <p>每个实例对应一个磁盘存储目录 + 一个 Lucene 索引目录：
 * <pre>
 *   &lt;dataDir&gt;/ab/cd/&lt;sessionId&gt;/storage/   实际文件落盘目录
 *   &lt;dataDir&gt;/ab/cd/&lt;sessionId&gt;/lucene/    全文索引目录
 * </pre>
 *
 * @author tangzq
 */
public class FileWorkspace implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(FileWorkspace.class);

    private final String sessionId;
    private final WorkspaceConfig config;
    private final String storagePath;
    private final String lucenePath;
    private final FileStorage fileStorage;
    private final LuceneStorage luceneStorage;

    public FileWorkspace(String sessionId, WorkspaceConfig config) {
        this.sessionId = sessionId;
        this.config = config;

        // 计算路径: <dataDir>/ab/cd/<sessionId>/（不立即创建磁盘目录，延迟到首次使用时）
        String dir = buildSessionDir(sessionId);
        this.storagePath = dir + File.separator + "storage";
        this.lucenePath = dir + File.separator + "lucene";

        // 初始化存储层（均为延迟初始化，构造时不触碰磁盘）
        this.fileStorage = new FileStorage(storagePath, config.getMapDir());
        this.luceneStorage = new LuceneStorage(lucenePath);

        log.info("FileWorkspace 已创建（延迟初始化）: sessionId={}, storagePath={}", sessionId, storagePath);
    }

    /**
     * 构建会话目录路径: <dataDir>/ab/cd/<sessionId>/
     */
    private String buildSessionDir(String sessionId) {
        String a = sessionId.substring(0, 2);
        String b = sessionId.substring(2, 4);
        return config.getDataDir() + File.separator + a + File.separator + b + File.separator + sessionId;
    }

    // ==================== 文档操作 ====================

    /**
     * 添加文档（支持分段）。
     *
     * <p>将 sourceBytes（若为 null 则用分段拼接文本）写入磁盘，
     * 并把分段拼接后的完整文本索引进 Lucene。docId 直接使用文件虚拟路径。
     *
     * @param fileName      原始文件名（如 report.pdf）
     * @param sourceBytes   源文件字节（可为 null）
     * @param filePath      目录路径（如 /docs）
     * @param textChunks    分段后的文本列表
     * @return 文档ID（即文件虚拟路径）
     */
    public String addDocument(String fileName, byte[] sourceBytes, String filePath, List<String> textChunks) {
        if (textChunks == null) {
            textChunks = Collections.singletonList("");
        }

        if (StringUtils.isBlank(fileName)) {
            fileName = "未命名-" + UUID.randomUUID().toString().replace("-", "") + ".txt";
        }

        // 组合完整虚拟路径
        String fullPath;
        if (StringUtils.endsWith(filePath, "/")) {
            fullPath = filePath + fileName;
        } else {
            fullPath = (filePath == null ? "/" : filePath) + "/" + fileName;
        }
        fullPath = FileStorage.normalizeVirtual(fullPath);

        String fullText = String.join("", textChunks);
        if (fullText == null) {
            fullText = "";
        }

        if (sourceBytes == null) {
            sourceBytes = fullText.getBytes(StandardCharsets.UTF_8);
        }

        try {
            // 1. 写入磁盘
            fileStorage.writeFile(fullPath, sourceBytes);
        } catch (IOException e) {
            throw new RuntimeException("写入文件失败: " + fullPath, e);
        }

        // 2. Lucene 索引（索引完整文本）
        if (luceneStorage != null && luceneStorage.isInitialized()) {
            luceneStorage.indexDocument(fullPath, fullPath, fullText);
        }

        log.info("文档添加成功: docId={}, fileName={}, chunks={}", fullPath, fileName, textChunks.size());
        return fullPath;
    }

    // ==================== 搜索操作 ====================

    /**
     * 搜索：返回相关文件列表（文件级别，非片段）。
     *
     * @param query      查询关键词
     * @param resultSize 最大返回数量
     * @return 相关文件列表
     */
    public List<SearchResult> search(String query, int resultSize) {

        List<ChunkMatch> chunkMatches = new ArrayList<>();

        // Lucene 全文检索
        if (luceneStorage != null && luceneStorage.isInitialized()) {
            List<SearchResult> luceneResults = luceneStorage.search(query, config.getLuceneTopN());
            for (SearchResult r : luceneResults) {
                chunkMatches.add(new ChunkMatch(r.getDocId(), 0, r.getScore(), "lucene"));
            }
            log.debug("Lucene 搜索返回 {} 个结果", luceneResults.size());
        }

        // 聚合到文件级别
        Map<String, SearchResult> fileResults = new LinkedHashMap<>();
        for (ChunkMatch match : chunkMatches) {
            String docId = match.docId;
            if (docId == null) {
                continue;
            }

            fileResults.computeIfAbsent(docId, id -> {
                Document doc = fileStorage.getDocumentByPath(id);
                if (doc == null) {
                    return null;
                }
                SearchResult sr = new SearchResult(doc.getDocId(), doc.getFileName(), doc.getFilePath(), 0.0, match.source);
                sr.setRealPath(doc.getRealPath());
                sr.setMappedPath(doc.getMappedPath());
                return sr;
            });

            SearchResult result = fileResults.get(docId);
            if (result != null) {
                if (match.score > result.getScore()) {
                    result.setScore(match.score);
                }
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
     * Grep 搜索（正则表达式）。
     *
     * @param regex      正则表达式
     * @param resultSize 最大返回数量
     * @return 匹配结果
     */
    public List<SearchResult> grep(String regex, int resultSize) {
        List<SearchResult> results = new ArrayList<>();

        List<FileStorage.GrepResult> grepResults = fileStorage.grepFiles(regex, resultSize);

        for (FileStorage.GrepResult grepResult : grepResults) {
            SearchResult result = new SearchResult(grepResult.getDocId(),
                    grepResult.getFileName(),
                    grepResult.getFilePath(),
                    1.0,
                    "grep");
            result.setRealPath(grepResult.getRealPath());
            result.setMappedPath(grepResult.getMappedPath());
            results.add(result);
        }

        return results;
    }

    // ==================== 文件操作 ====================

    /**
     * 获取源文件（通过路径）。
     */
    public byte[] getSourceFileByPath(String filePath) {
        try {
            return fileStorage.readFile(filePath);
        } catch (IOException e) {
            log.warn("读取源文件失败: {}", filePath);
            return null;
        }
    }

    /**
     * 获取知识文本（通过路径），以 UTF-8 解码。
     */
    public String getKnowledgeTextByPath(String filePath) {
        byte[] data = getSourceFileByPath(filePath);
        if (data == null) {
            return null;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * 删除文档（docId 即文件虚拟路径），同时删除磁盘文件与 Lucene 索引。
     */
    public void deleteDocument(String docId) {
        try {
            fileStorage.deleteFile(docId);
        } catch (IOException e) {
            log.warn("删除文件失败: {}", docId);
        }
        if (luceneStorage != null) {
            luceneStorage.deleteDocument(docId);
        }
        log.info("文档已删除: docId={}", docId);
    }

    /**
     * 列出所有文档。
     */
    public List<Document> listDocuments() {
        return fileStorage.listDocuments();
    }

    // ==================== 索引同步（供 Workspace 目录操作调用）====================

    /**
     * 目录移动/重命名后同步 Lucene 索引：删除旧前缀下的索引，按新路径重新索引。
     *
     * @param oldDirPath 旧目录虚拟路径（不带尾部 /）
     * @param newDirPath 新目录虚拟路径（不带尾部 /）
     */
    public void reindexAfterDirMove(String oldDirPath, String newDirPath) {
        if (luceneStorage == null || !luceneStorage.isInitialized()) {
            return;
        }
        String oldPrefix = oldDirPath.endsWith("/") ? oldDirPath : oldDirPath + "/";
        // 删除旧前缀的所有索引
        luceneStorage.deleteByPrefix(oldPrefix);
        // 对新目录下所有文件重新建立索引
        for (String path : fileStorage.listFilePathsUnder(newDirPath)) {
            String text = getKnowledgeTextByPath(path);
            luceneStorage.indexDocument(path, path, text == null ? "" : text);
        }
    }

    /**
     * 删除目录后同步删除该目录前缀下的所有 Lucene 索引。
     */
    public void deleteIndexByPrefix(String dirPath) {
        if (luceneStorage == null || !luceneStorage.isInitialized()) {
            return;
        }
        String prefix = dirPath.endsWith("/") ? dirPath : dirPath + "/";
        luceneStorage.deleteByPrefix(prefix);
    }

    // ==================== 文件系统 ====================

    /**
     * 获取文件系统。
     *
     * <p>注意：文件存储版直接使用系统默认文件系统（真实磁盘），
     * 请配合 {@link FileStorage#toReal(String)} 将虚拟路径转换为真实路径后使用。
     */
    public FileSystem getFileSystem() {
        return FileSystems.getDefault();
    }

    /**
     * 获取工作空间统计信息。
     */
    public KBInfo getInfo() {
        return fileStorage.getInfo(sessionId);
    }

    public FileStorage getFileStorage() {
        return fileStorage;
    }

    /**
     * 判断该会话工作空间是否已完成初始化（即磁盘存储目录已被实际创建）。
     * 仅在首次写入/创建目录（上传）时才初始化；未初始化时返回 false。
     */
    public boolean isInitialized() {
        return fileStorage.isInitialized();
    }

    // ==================== 生命周期 ====================

    @Override
    public void close() {
        if (luceneStorage != null) {
            luceneStorage.close();
        }
        log.info("FileWorkspace 已关闭: sessionId={}", sessionId);
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
