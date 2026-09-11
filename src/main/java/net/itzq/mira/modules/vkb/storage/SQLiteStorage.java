package net.itzq.mira.modules.vkb.storage;

import net.itzq.mira.modules.vkb.VKBConfig;
import net.itzq.mira.modules.vkb.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQLite 存储层 - 管理文档、分段、向量
 *
 * @author tangzq
 */
public class SQLiteStorage implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SQLiteStorage.class);

    private final String dbPath;
    private final VKBConfig config;
    private Connection connection;

    public SQLiteStorage(String dbPath, VKBConfig config) throws Exception {
        this.dbPath = dbPath;
        this.config = config;
        initDatabase();
    }

    // ==================== 初始化 ====================

    private void initDatabase() throws Exception {
        // 确保目录存在
        File dbFile = new File(dbPath);
        dbFile.getParentFile().mkdirs();

        // 连接数据库
        String url = "jdbc:sqlite:" + dbPath + "?enable_load_extension=true";
        connection = DriverManager.getConnection(url);

        // 启用 WAL 模式（更好的并发性能）
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
        }

        // 加载 sqlite-vec 扩展
//        if (config.isVecEnabled()) {
//            loadSqliteVecExtension();
//        }

        // 创建表
        createTables();

        log.info("SQLiteStorage 初始化完成: {}", dbPath);
    }

    private boolean sqliteVecLoaded = false;

    private void loadSqliteVecExtension() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String libFileName = os.contains("win") ? "vec0.dll" :
                    (os.contains("mac") ? "vec0.dylib" : "vec0.so");

            String platformDir;
            if (os.contains("win")) {
                platformDir = "sqlite-vec-0.1.9-loadable-windows-x86_64";
            } else if (os.contains("linux")) {
                String arch = System.getProperty("os.arch").toLowerCase();
                platformDir = arch.contains("aarch64") || arch.contains("arm64")
                        ? "sqlite-vec-0.1.9-loadable-linux-aarch64"
                        : "sqlite-vec-0.1.9-loadable-linux-x86_64";
            } else {
                platformDir = "sqlite-vec-0.1.9-loadable-macos-x86_64";
            }

            String libsDir = config.getSqliteVecLibsDir();
            String libPath = new File(libsDir, platformDir + "/" + libFileName).getAbsolutePath();

            File libFile = new File(libPath);
            log.info("尝试加载 sqlite-vec 扩展:");
            log.info("  libsDir={}", new File(libsDir).getAbsolutePath());
            log.info("  libPath={}", libPath);
            log.info("  exists={}", libFile.exists());

            if (!libFile.exists()) {
                log.error("sqlite-vec 扩展文件不存在，请检查 libs-dir 配置: {}", libsDir);
                sqliteVecLoaded = false;
                return;
            }

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SELECT load_extension('" + libPath.replace("\\", "\\\\") + "')");

                // 验证扩展是否可用
                ResultSet rs = stmt.executeQuery("SELECT vec_version()");
                if (rs.next()) {
                    log.info("sqlite-vec 扩展加载成功，版本: {}", rs.getString(1));
                    sqliteVecLoaded = true;
                }
            }
        } catch (Exception e) {
            log.error("sqlite-vec 扩展加载失败: {}", e.getMessage());
            sqliteVecLoaded = false;
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // 文档表
            stmt.execute("CREATE TABLE IF NOT EXISTS documents (" +
                    "doc_id TEXT PRIMARY KEY, " +
                    "file_name TEXT NOT NULL, " +
                    "file_path TEXT NOT NULL, " +
                    "file_ext TEXT, " +
                    "source_size INTEGER DEFAULT 0, " +
                    "text_size INTEGER DEFAULT 0, " +
                    "chunk_count INTEGER DEFAULT 0, " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL)");

            // 源文件表
            stmt.execute("CREATE TABLE IF NOT EXISTS source_files (" +
                    "doc_id TEXT PRIMARY KEY, " +
                    "data BLOB NOT NULL, " +
                    "FOREIGN KEY (doc_id) REFERENCES documents(doc_id))");

            // 知识文本表
            stmt.execute("CREATE TABLE IF NOT EXISTS knowledge_texts (" +
                    "doc_id TEXT PRIMARY KEY, " +
                    "content TEXT NOT NULL, " +
                    "FOREIGN KEY (doc_id) REFERENCES documents(doc_id))");

            // 分段表
            stmt.execute("CREATE TABLE IF NOT EXISTS chunks (" +
                    "chunk_id TEXT PRIMARY KEY, " +
                    "doc_id TEXT NOT NULL, " +
                    "chunk_index INTEGER NOT NULL, " +
                    "content TEXT NOT NULL, " +
                    "start_offset INTEGER DEFAULT 0, " +
                    "end_offset INTEGER DEFAULT 0, " +
                    "created_at INTEGER NOT NULL, " +
                    "UNIQUE(doc_id, chunk_index), " +
                    "FOREIGN KEY (doc_id) REFERENCES documents(doc_id))");

            // 目录表（网盘式文件夹支持）
            stmt.execute("CREATE TABLE IF NOT EXISTS directories (" +
                    "dir_id TEXT PRIMARY KEY, " +
                    "dir_path TEXT NOT NULL UNIQUE, " +
                    "dir_name TEXT NOT NULL, " +
                    "parent_path TEXT, " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL)");

            // 索引
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_documents_path ON documents(file_path)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON chunks(doc_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_directories_path ON directories(dir_path)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_directories_parent ON directories(parent_path)");
        }

        // 确保根目录存在
        ensureRootDirectory();

        // 向量表（需要 sqlite-vec 扩展）
//        if (config.isVecEnabled() && config.getEmbeddingProvider() != null) {
//            if (!sqliteVecLoaded) {
//                log.warn("sqlite-vec 扩展未加载，跳过向量表创建");
//            } else {
//                int dimension = config.getEmbeddingProvider().getDimension();
//                try (Statement stmt = connection.createStatement()) {
//                    // 检查维度是否一致
//                    int existingDimension = getVectorTableDimension(stmt);
//                    if (existingDimension != -1 && existingDimension != dimension) {
//                        log.warn("向量表维度不匹配: 期望={}, 实际={}，将删除重建", dimension, existingDimension);
//                        stmt.execute("DROP TABLE IF EXISTS vectors");
//                    }
//
//                    // 创建向量表
//                    stmt.execute("CREATE VIRTUAL TABLE IF NOT EXISTS vectors USING vec0(" +
//                            "chunk_id TEXT PRIMARY KEY, " +
//                            "embedding float[" + dimension + "])");
//                    log.info("向量表创建成功，维度: {}", dimension);
//                } catch (SQLException e) {
//                    log.error("向量表创建失败: {}", e.getMessage());
//                    throw e; // 抛出异常，让调用方知道向量表创建失败
//                }
//            }
//        }
    }

    /**
     * 获取向量表的维度
     * 从 sqlite_master 中解析建表 SQL 获取 float[N] 的 N
     *
     * @return 维度数，-1 表示表不存在
     */
    private int getVectorTableDimension(Statement stmt) {
        try {
            ResultSet rs = stmt.executeQuery(
                    "SELECT sql FROM sqlite_master WHERE type='table' AND name='vectors'");
            if (rs.next()) {
                String sql = rs.getString("sql");
                if (sql != null && sql.contains("float[")) {
                    int start = sql.indexOf("float[") + 6;
                    int end = sql.indexOf("]", start);
                    if (start > 5 && end > start) {
                        return Integer.parseInt(sql.substring(start, end));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("获取向量表维度失败: {}", e.getMessage());
        }
        return -1;
    }

    // ==================== 文档操作 ====================

    public void insertDocument(Document doc) {
        String sql = "INSERT OR REPLACE INTO documents " +
                "(doc_id, file_name, file_path, file_ext, source_size, text_size, chunk_count, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, doc.getDocId());
            pstmt.setString(2, doc.getFileName());
            pstmt.setString(3, doc.getFilePath());
            pstmt.setString(4, doc.getFileExt());
            pstmt.setLong(5, doc.getSourceSize());
            pstmt.setLong(6, doc.getTextSize());
            pstmt.setInt(7, doc.getChunkCount());
            pstmt.setLong(8, doc.getCreatedAt());
            pstmt.setLong(9, doc.getUpdatedAt());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("插入文档失败: docId={}, fileName={}, filePath={}", doc.getDocId(), doc.getFileName(), doc.getFilePath(), e);
            throw new RuntimeException("插入文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 重建向量表（当维度变化时调用）
     *
     * @param newDimension 新的向量维度
     */
    public void recreateVectorTable(int newDimension) {
        if (!sqliteVecLoaded) {
            log.warn("sqlite-vec 扩展未加载，无法重建向量表");
            return;
        }

        try (Statement stmt = connection.createStatement()) {
            // 删除旧表
            stmt.execute("DROP TABLE IF EXISTS vectors");
            log.info("已删除旧向量表");

            // 创建新表
            stmt.execute("CREATE VIRTUAL TABLE IF NOT EXISTS vectors USING vec0(" +
                    "chunk_id TEXT PRIMARY KEY, " +
                    "embedding float[" + newDimension + "])");
            log.info("向量表重建成功，新维度: {}", newDimension);
        } catch (SQLException e) {
            log.error("重建向量表失败: {}", e.getMessage());
            throw new RuntimeException("重建向量表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取当前向量表的维度
     *
     * @return 维度数，-1 表示表不存在
     */
    public int getCurrentVectorDimension() {
        try (Statement stmt = connection.createStatement()) {
            return getVectorTableDimension(stmt);
        } catch (SQLException e) {
            return -1;
        }
    }

    public Document getDocument(String docId) {
        String sql = "SELECT * FROM documents WHERE doc_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, docId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return mapDocument(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询文档失败", e);
        }
        return null;
    }

    public Document getDocumentByPath(String filePath) {
        String sql = "SELECT * FROM documents WHERE file_path = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, filePath);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return mapDocument(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询文档失败", e);
        }
        return null;
    }

    public List<Document> listDocuments() {
        List<Document> docs = new ArrayList<>();
        String sql = "SELECT * FROM documents ORDER BY created_at DESC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                docs.add(mapDocument(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("列出文档失败", e);
        }
        return docs;
    }

    public void deleteDocument(String docId) {
        try {
            connection.setAutoCommit(false);
            try {
                // 删除向量
//                try (PreparedStatement pstmt = connection.prepareStatement(
//                        "DELETE FROM vectors WHERE chunk_id IN (SELECT chunk_id FROM chunks WHERE doc_id = ?)")) {
//                    pstmt.setString(1, docId);
//                    pstmt.executeUpdate();
//                }

                // 删除分段
                try (PreparedStatement pstmt = connection.prepareStatement(
                        "DELETE FROM chunks WHERE doc_id = ?")) {
                    pstmt.setString(1, docId);
                    pstmt.executeUpdate();
                }

                // 删除知识文本
                try (PreparedStatement pstmt = connection.prepareStatement(
                        "DELETE FROM knowledge_texts WHERE doc_id = ?")) {
                    pstmt.setString(1, docId);
                    pstmt.executeUpdate();
                }

                // 删除源文件
                try (PreparedStatement pstmt = connection.prepareStatement(
                        "DELETE FROM source_files WHERE doc_id = ?")) {
                    pstmt.setString(1, docId);
                    pstmt.executeUpdate();
                }

                // 删除文档
                try (PreparedStatement pstmt = connection.prepareStatement(
                        "DELETE FROM documents WHERE doc_id = ?")) {
                    pstmt.setString(1, docId);
                    pstmt.executeUpdate();
                }

                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("删除文档失败", e);
        }
    }

    private Document mapDocument(ResultSet rs) throws SQLException {
        Document doc = new Document();
        doc.setDocId(rs.getString("doc_id"));
        doc.setFileName(rs.getString("file_name"));
        doc.setFilePath(rs.getString("file_path"));
        doc.setFileExt(rs.getString("file_ext"));
        doc.setSourceSize(rs.getLong("source_size"));
        doc.setTextSize(rs.getLong("text_size"));
        doc.setChunkCount(rs.getInt("chunk_count"));
        doc.setCreatedAt(rs.getLong("created_at"));
        doc.setUpdatedAt(rs.getLong("updated_at"));
        return doc;
    }

    // ==================== 源文件操作 ====================

    public void storeSourceFile(String docId, byte[] data) {
        String sql = "INSERT OR REPLACE INTO source_files (doc_id, data) VALUES (?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, docId);
            pstmt.setBytes(2, data);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("存储源文件失败", e);
        }
    }

    public byte[] getSourceFile(String docId) {
        String sql = "SELECT data FROM source_files WHERE doc_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, docId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBytes("data");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取源文件失败", e);
        }
        return null;
    }

    // ==================== 知识文本操作 ====================

    public void storeKnowledgeText(String docId, String content) {
        String sql = "INSERT OR REPLACE INTO knowledge_texts (doc_id, content) VALUES (?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, docId);
            pstmt.setString(2, content);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("存储知识文本失败", e);
        }
    }

    public String getKnowledgeText(String docId) {
        String sql = "SELECT content FROM knowledge_texts WHERE doc_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, docId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("content");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取知识文本失败", e);
        }
        return null;
    }

    // ==================== 分段操作 ====================

    public void storeChunk(Chunk chunk) {
        String sql = "INSERT OR REPLACE INTO chunks " +
                "(chunk_id, doc_id, chunk_index, content, start_offset, end_offset, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, chunk.getChunkId());
            pstmt.setString(2, chunk.getDocId());
            pstmt.setInt(3, chunk.getChunkIndex());
            pstmt.setString(4, chunk.getContent());
            pstmt.setInt(5, chunk.getStartOffset());
            pstmt.setInt(6, chunk.getEndOffset());
            pstmt.setLong(7, chunk.getCreatedAt());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("存储分段失败", e);
        }
    }

    public List<Chunk> getChunksByDocId(String docId) {
        List<Chunk> chunks = new ArrayList<>();
        String sql = "SELECT * FROM chunks WHERE doc_id = ? ORDER BY chunk_index";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, docId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    chunks.add(mapChunk(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询分段失败", e);
        }
        return chunks;
    }

    public Chunk getChunk(String chunkId) {
        String sql = "SELECT * FROM chunks WHERE chunk_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, chunkId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return mapChunk(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询分段失败", e);
        }
        return null;
    }

    private Chunk mapChunk(ResultSet rs) throws SQLException {
        Chunk chunk = new Chunk();
        chunk.setChunkId(rs.getString("chunk_id"));
        chunk.setDocId(rs.getString("doc_id"));
        chunk.setChunkIndex(rs.getInt("chunk_index"));
        chunk.setContent(rs.getString("content"));
        chunk.setStartOffset(rs.getInt("start_offset"));
        chunk.setEndOffset(rs.getInt("end_offset"));
        chunk.setCreatedAt(rs.getLong("created_at"));
        return chunk;
    }

    // ==================== 向量操作 ====================

    public void storeVector(String chunkId, float[] embedding) {
        if (!sqliteVecLoaded) {
            return;
        }

        if (embedding == null || embedding.length == 0) {
            log.warn("向量为空，跳过存储: chunkId={}", chunkId);
            return;
        }

        // 检查向量表是否存在
        if (!isVectorTableExists()) {
            log.warn("向量表不存在，跳过存储: chunkId={}", chunkId);
            return;
        }

        String sql = "INSERT OR REPLACE INTO vectors (chunk_id, embedding) VALUES (?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, chunkId);
            pstmt.setBytes(2, serializeFloatArray(embedding));
            pstmt.executeUpdate();
            log.debug("存储向量成功: chunkId={}, dim={}", chunkId, embedding.length);
        } catch (SQLException e) {
            log.error("存储向量失败: chunkId={}, embeddingDim={}, error={}", chunkId, embedding.length, e.getMessage());
            throw new RuntimeException("存储向量失败: " + e.getMessage(), e);
        }
    }

    /**
     * 检查向量表是否存在
     */
    private boolean isVectorTableExists() {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='vectors'");
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 获取向量表中的记录数
     */
    public int getVectorCount() {
        if (!isVectorTableExists()) {
            return 0;
        }
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM vectors");
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.warn("获取向量数量失败: {}", e.getMessage());
        }
        return 0;
    }

    public List<VectorMatch> searchSimilarVectors(float[] queryVector, int limit) {
        List<VectorMatch> results = new ArrayList<>();

        // 检查前置条件
        if ( !sqliteVecLoaded) {
            log.debug("向量搜索跳过:  sqliteVecLoaded={}", sqliteVecLoaded);
            return results;
        }

        if (!isVectorTableExists()) {
            log.warn("向量表不存在，跳过搜索");
            return results;
        }

        String sql = "SELECT chunk_id, distance FROM vectors " +
                "WHERE embedding MATCH ? AND k = ? ORDER BY distance";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            byte[] queryBytes = serializeFloatArray(queryVector);
            pstmt.setBytes(1, queryBytes);
            pstmt.setInt(2, limit);

            log.info("执行向量搜索: limit={}, queryVectorDim={}, queryBytesLen={}", limit, queryVector.length, queryBytes.length);

            try (ResultSet rs = pstmt.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    String chunkId = rs.getString("chunk_id");
                    double distance = rs.getDouble("distance");
                    double similarity = 1.0 - distance;

                    log.info("  向量结果 #{}: chunkId={}, distance={}, similarity={}", count, chunkId, distance, similarity);

                    // 获取分段信息
                    Chunk chunk = getChunk(chunkId);
                    if (chunk != null) {
                        results.add(new VectorMatch(chunkId, chunk.getDocId(),
                                chunk.getChunkIndex(), similarity,
                                chunk.getContent()));
                    } else {
                        log.warn("  分段不存在: chunkId={}", chunkId);
                    }
                }
            }

            log.info("向量搜索完成: 返回 {} 个结果", results.size());

        } catch (SQLException e) {
            log.error("向量搜索失败: {}", e.getMessage(), e);
        }
        return results;
    }

    // ==================== 目录操作（用于 FS） ====================

    public List<String> listDirectory(String dirPath) {
        List<String> entries = new ArrayList<>();

        // 规范化路径
        String normalized = normalizeDirPath(dirPath);

        try {
            // 1. 查询 directories 表中的直接子目录
            String dirSql = "SELECT dir_name FROM directories WHERE parent_path = ?";
            try (PreparedStatement pstmt = connection.prepareStatement(dirSql)) {
                pstmt.setString(1, normalized);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String dirName = rs.getString("dir_name");
                        entries.add(dirName + "/");
                    }
                }
            }

            // 2. 查询该目录下的直接子文件
            String fileSql = "SELECT DISTINCT file_path FROM documents " +
                    "WHERE file_path LIKE ? AND file_path NOT LIKE ?";
            try (PreparedStatement pstmt = connection.prepareStatement(fileSql)) {
                pstmt.setString(1, normalized + "%");
                pstmt.setString(2, normalized + "%/%");
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String filePath = rs.getString("file_path");
                        String name = filePath.substring(normalized.length());
                        entries.add(name);
                    }
                }
            }

            // 3. 兼容：推导未注册到 directories 表的隐式子目录
            String implicitDirSql = "SELECT file_path FROM documents WHERE file_path LIKE ?";
            try (PreparedStatement pstmt = connection.prepareStatement(implicitDirSql)) {
                pstmt.setString(1, normalized + "%/%");
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String filePath = rs.getString("file_path");
                        String remaining = filePath.substring(normalized.length());
                        int slashIdx = remaining.indexOf('/');
                        if (slashIdx > 0) {
                            String dirName = remaining.substring(0, slashIdx) + "/";
                            if (!entries.contains(dirName)) {
                                entries.add(dirName);
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("列出目录失败: {}", e.getMessage());
        }
        return entries;
    }

    public boolean existsPath(String path) {
        if (path == null) return false;
        String normalized = normalizeDirPath(path);

        // 检查是否为目录（directories 表精确匹配）
        if (isDirectoryRecord(normalized)) {
            return true;
        }

        // 检查是否为文件（documents 表精确匹配）
        String fileCheckPath = normalized.endsWith("/") && normalized.length() > 1
                ? normalized.substring(0, normalized.length() - 1) : normalized;
        String sql = "SELECT COUNT(*) FROM documents WHERE file_path = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, fileCheckPath);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return true;
                }
            }
        } catch (SQLException e) {
            // ignore
        }

        // 兼容：检查是否为隐式目录（有子文件但无目录记录的情况）
        String dirSql = "SELECT COUNT(*) FROM documents WHERE file_path LIKE ?";
        try (PreparedStatement pstmt = connection.prepareStatement(dirSql)) {
            pstmt.setString(1, normalized + "%");
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean isDirectory(String path) {
        if (path == null) return false;
        String normalized = normalizeDirPath(path);

        // 先查 directories 表
        if (isDirectoryRecord(normalized)) {
            return true;
        }

        // 兼容隐式目录（有子文件但无目录记录）
        String sql = "SELECT COUNT(*) FROM documents WHERE file_path LIKE ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, normalized + "%");
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    // ==================== 目录管理（directories 表） ====================

    /**
     * 确保根目录 / 存在
     */
    private void ensureRootDirectory() {
        try {
            String sql = "INSERT OR IGNORE INTO directories (dir_id, dir_path, dir_name, parent_path, created_at, updated_at) " +
                    "VALUES (?, ?, ?, NULL, ?, ?)";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                long now = System.currentTimeMillis();
                pstmt.setString(1, "root");
                pstmt.setString(2, "/");
                pstmt.setString(3, "/");
                pstmt.setLong(4, now);
                pstmt.setLong(5, now);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("创建根目录失败: {}", e.getMessage());
        }
    }

    /**
     * 规范化目录路径：确保以 / 开头、以 / 结尾
     */
    private String normalizeDirPath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String normalized = path.replace("\\", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        if (normalized.length() > 1 && normalized.equals("//")) {
            normalized = "/";
        }
        return normalized;
    }

    /**
     * 从目录路径中提取目录名
     */
    private String extractDirName(String dirPath) {
        String trimmed = dirPath.endsWith("/") && dirPath.length() > 1
                ? dirPath.substring(0, dirPath.length() - 1) : dirPath;
        int lastSlash = trimmed.lastIndexOf('/');
        return lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
    }

    /**
     * 从目录路径中提取父目录路径
     */
    private String extractParentPath(String dirPath) {
        String trimmed = dirPath.endsWith("/") && dirPath.length() > 1
                ? dirPath.substring(0, dirPath.length() - 1) : dirPath;
        int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "/";  // 根目录的父是它自己
        }
        return trimmed.substring(0, lastSlash + 1);
    }

    /**
     * 检查 directories 表中是否存在指定路径的目录记录
     */
    private boolean isDirectoryRecord(String dirPath) {
        String normalized = normalizeDirPath(dirPath);
        String sql = "SELECT COUNT(*) FROM directories WHERE dir_path = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, normalized);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 创建目录（级联创建所有不存在的父目录）
     *
     * @param dirPath 目录路径，如 /docs/reports/
     * @return 已创建的目录数量（已存在的不计）
     */
    public int createDirectory(String dirPath) {
        String normalized = normalizeDirPath(dirPath);
        if (normalized.equals("/")) {
            return 0; // 根目录已存在
        }

        // 收集需要创建的目录链（从根到目标）
        List<String> chain = new ArrayList<>();
        String current = normalized;
        while (!current.equals("/") && !isDirectoryRecord(current)) {
            chain.add(0, current);
            current = extractParentPath(current);
        }

        if (chain.isEmpty()) {
            return 0; // 目录已存在
        }

        int created = 0;
        long now = System.currentTimeMillis();
        String sql = "INSERT OR IGNORE INTO directories (dir_id, dir_path, dir_name, parent_path, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            for (String dir : chain) {
                pstmt.setString(1, UUID.randomUUID().toString().replace("-", ""));
                pstmt.setString(2, dir);
                pstmt.setString(3, extractDirName(dir));
                pstmt.setString(4, extractParentPath(dir));
                pstmt.setLong(5, now);
                pstmt.setLong(6, now);
                created += pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("创建目录失败: {}", e.getMessage());
            throw new RuntimeException("创建目录失败: " + e.getMessage(), e);
        }
        log.info("目录创建完成: {} (新建 {} 个)", normalized, created);
        return created;
    }

    /**
     * 删除目录
     *
     * @param dirPath  目录路径
     * @param recursive 是否递归删除子目录和子文件
     * @throws SQLException 删除失败
     */
    public void deleteDirectory(String dirPath, boolean recursive) throws SQLException {
        String normalized = normalizeDirPath(dirPath);
        if (normalized.equals("/")) {
            throw new IllegalArgumentException("不能删除根目录");
        }

        if (!recursive) {
            // 非递归：检查目录是否为空
            boolean hasFiles = hasFilesInDirectory(normalized);
            boolean hasSubDirs = hasSubDirectories(normalized);
            if (hasFiles || hasSubDirs) {
                throw new IllegalStateException("目录不为空，无法删除: " + dirPath);
            }
            // 删除目录记录
            String sql = "DELETE FROM directories WHERE dir_path = ?";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, normalized);
                pstmt.executeUpdate();
            }
            return;
        }

        // 递归删除：事务保证一致性
        try {
            connection.setAutoCommit(false);
            try {
                // 1. 找出所有子目录（含自身）
                List<String> allDirs = new ArrayList<>();
                allDirs.add(normalized);
                collectSubDirectories(normalized, allDirs);

                // 2. 删除所有子目录下的文件
                for (String dir : allDirs) {
                    List<String> docIds = getDocIdsUnderDirectory(dir);
                    for (String docId : docIds) {
                        deleteDocumentInternal(docId);
                    }
                }

                // 3. 删除所有子目录记录（从最深层开始）
                allDirs.sort((a, b) -> Integer.compare(
                        countSlash(b), countSlash(a)));
                String deleteDirSql = "DELETE FROM directories WHERE dir_path = ?";
                try (PreparedStatement pstmt = connection.prepareStatement(deleteDirSql)) {
                    for (String dir : allDirs) {
                        pstmt.setString(1, dir);
                        pstmt.executeUpdate();
                    }
                }

                connection.commit();
                log.info("递归删除目录完成: {} (删除 {} 个目录)", normalized, allDirs.size());
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("递归删除目录失败: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 重命名/移动目录（级联更新所有子目录和子文件的路径）
     *
     * @param oldPath 原目录路径，如 /docs/old/
     * @param newPath 新目录路径，如 /docs/new/
     */
    public void renameDirectory(String oldPath, String newPath) throws SQLException {
        String oldNorm = normalizeDirPath(oldPath);
        String newNorm = normalizeDirPath(newPath);

        if (oldNorm.equals("/")) {
            throw new IllegalArgumentException("不能重命名根目录");
        }
        if (oldNorm.equals(newNorm)) {
            return;
        }
        if (newNorm.startsWith(oldNorm)) {
            throw new IllegalArgumentException("不能将目录移动到其子目录下");
        }

        // 确保新路径的父目录存在
        String newParent = extractParentPath(newNorm);
        if (!newParent.equals("/") && !isDirectoryRecord(newParent)) {
            createDirectory(newParent);
        }

        try {
            connection.setAutoCommit(false);
            try {
                long now = System.currentTimeMillis();

                // 1. 更新所有子目录的 dir_path 和 parent_path
                //    子目录 = dir_path 以 oldNorm 开头的所有目录
                String queryDirsSql = "SELECT dir_id, dir_path, parent_path FROM directories WHERE dir_path LIKE ? OR dir_path = ?";
                List<String[]> dirUpdates = new ArrayList<>();
                try (PreparedStatement pstmt = connection.prepareStatement(queryDirsSql)) {
                    pstmt.setString(1, oldNorm + "%");
                    pstmt.setString(2, oldNorm);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            String dirId = rs.getString("dir_id");
                            String dirPath = rs.getString("dir_path");
                            String parentPath = rs.getString("parent_path");
                            // 计算新路径
                            String newDirPath = newNorm + dirPath.substring(oldNorm.length());
                            String newParentPath;
                            if (parentPath != null && parentPath.startsWith(oldNorm)) {
                                newParentPath = newNorm + parentPath.substring(oldNorm.length());
                            } else if (dirPath.equals(oldNorm)) {
                                newParentPath = extractParentPath(newNorm);
                            } else {
                                newParentPath = parentPath;
                            }
                            dirUpdates.add(new String[]{dirId, newDirPath, newParentPath});
                        }
                    }
                }

                String updateDirSql = "UPDATE directories SET dir_path = ?, dir_name = ?, parent_path = ?, updated_at = ? WHERE dir_id = ?";
                try (PreparedStatement pstmt = connection.prepareStatement(updateDirSql)) {
                    for (String[] update : dirUpdates) {
                        pstmt.setString(1, update[1]);
                        pstmt.setString(2, extractDirName(update[1]));
                        pstmt.setString(3, update[2]);
                        pstmt.setLong(4, now);
                        pstmt.setString(5, update[0]);
                        pstmt.executeUpdate();
                    }
                }

                // 2. 更新所有子文件的 file_path
                String updateFilesSql = "UPDATE documents SET file_path = ?, updated_at = ? WHERE file_path LIKE ?";
                try (PreparedStatement pstmt = connection.prepareStatement(updateFilesSql)) {
                    // 构造新路径: newPath + 原路径去掉 oldPath 前缀
                    // SQLite 不支持 REPLACE 的位置参数，所以需要先查后更新
                    String queryFilesSql = "SELECT doc_id, file_path FROM documents WHERE file_path LIKE ?";
                    List<String[]> fileUpdates = new ArrayList<>();
                    try (PreparedStatement qPstmt = connection.prepareStatement(queryFilesSql)) {
                        qPstmt.setString(1, oldNorm + "%");
                        try (ResultSet rs = qPstmt.executeQuery()) {
                            while (rs.next()) {
                                String docId = rs.getString("doc_id");
                                String filePath = rs.getString("file_path");
                                String newFilePath = newNorm + filePath.substring(oldNorm.length());
                                fileUpdates.add(new String[]{docId, newFilePath});
                            }
                        }
                    }
                    // 逐条更新文件路径
                    String updateOneFileSql = "UPDATE documents SET file_path = ?, updated_at = ? WHERE doc_id = ?";
                    try (PreparedStatement uPstmt = connection.prepareStatement(updateOneFileSql)) {
                        for (String[] update : fileUpdates) {
                            uPstmt.setString(1, update[1]);
                            uPstmt.setLong(2, now);
                            uPstmt.setString(3, update[0]);
                            uPstmt.executeUpdate();
                        }
                    }
                }

                connection.commit();
                log.info("目录重命名完成: {} -> {}", oldNorm, newNorm);
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("重命名目录失败: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 获取目录下的直接子目录列表
     */
    public List<Directory> listSubDirectories(String dirPath) {
        String normalized = normalizeDirPath(dirPath);
        List<Directory> dirs = new ArrayList<>();
        String sql = "SELECT * FROM directories WHERE parent_path = ? ORDER BY dir_name";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, normalized);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    dirs.add(mapDirectory(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("列出子目录失败: {}", e.getMessage());
        }
        return dirs;
    }

    /**
     * 获取目录信息
     */
    public Directory getDirectory(String dirPath) {
        String normalized = normalizeDirPath(dirPath);
        String sql = "SELECT * FROM directories WHERE dir_path = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, normalized);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return mapDirectory(rs);
                }
            }
        } catch (SQLException e) {
            log.warn("查询目录失败: {}", e.getMessage());
        }
        return null;
    }

    // ==================== 目录操作辅助方法 ====================

    /**
     * 检查目录下是否有文件
     */
    private boolean hasFilesInDirectory(String dirPath) {
        String sql = "SELECT COUNT(*) FROM documents WHERE file_path LIKE ? AND file_path NOT LIKE ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, dirPath + "%");
            pstmt.setString(2, dirPath + "%/%");
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 检查目录下是否有子目录
     */
    private boolean hasSubDirectories(String dirPath) {
        String sql = "SELECT COUNT(*) FROM directories WHERE parent_path = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, dirPath);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 递归收集所有子目录（含自身）
     */
    private void collectSubDirectories(String dirPath, List<String> result) {
        String sql = "SELECT dir_path FROM directories WHERE parent_path = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, dirPath);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String subDir = rs.getString("dir_path");
                    result.add(subDir);
                    collectSubDirectories(subDir, result);
                }
            }
        } catch (SQLException e) {
            log.warn("收集子目录失败: {}", e.getMessage());
        }
    }

    /**
     * 获取目录下的所有文档ID（直接子文件）
     */
    private List<String> getDocIdsUnderDirectory(String dirPath) {
        List<String> docIds = new ArrayList<>();
        String sql = "SELECT doc_id FROM documents WHERE file_path LIKE ? AND file_path NOT LIKE ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, dirPath + "%");
            pstmt.setString(2, dirPath + "%/%");
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    docIds.add(rs.getString("doc_id"));
                }
            }
        } catch (SQLException e) {
            log.warn("获取目录下文档失败: {}", e.getMessage());
        }
        return docIds;
    }

    /**
     * 内部删除文档（不管理事务，由调用方控制）
     */
    private void deleteDocumentInternal(String docId) throws SQLException {
        // 删除向量
        try (PreparedStatement pstmt = connection.prepareStatement(
                "DELETE FROM vectors WHERE chunk_id IN (SELECT chunk_id FROM chunks WHERE doc_id = ?)")) {
            pstmt.setString(1, docId);
            pstmt.executeUpdate();
        } catch (SQLException ignored) {
            // vectors 表可能不存在
        }
        // 删除分段
        try (PreparedStatement pstmt = connection.prepareStatement(
                "DELETE FROM chunks WHERE doc_id = ?")) {
            pstmt.setString(1, docId);
            pstmt.executeUpdate();
        }
        // 删除知识文本
        try (PreparedStatement pstmt = connection.prepareStatement(
                "DELETE FROM knowledge_texts WHERE doc_id = ?")) {
            pstmt.setString(1, docId);
            pstmt.executeUpdate();
        }
        // 删除源文件
        try (PreparedStatement pstmt = connection.prepareStatement(
                "DELETE FROM source_files WHERE doc_id = ?")) {
            pstmt.setString(1, docId);
            pstmt.executeUpdate();
        }
        // 删除文档
        try (PreparedStatement pstmt = connection.prepareStatement(
                "DELETE FROM documents WHERE doc_id = ?")) {
            pstmt.setString(1, docId);
            pstmt.executeUpdate();
        }
    }

    /**
     * 统计路径中 / 的数量
     */
    private int countSlash(String path) {
        int count = 0;
        for (char c : path.toCharArray()) {
            if (c == '/') count++;
        }
        return count;
    }

    /**
     * 将 ResultSet 映射为 Directory 对象
     */
    private Directory mapDirectory(ResultSet rs) throws SQLException {
        Directory dir = new Directory();
        dir.setDirId(rs.getString("dir_id"));
        dir.setDirPath(rs.getString("dir_path"));
        dir.setDirName(rs.getString("dir_name"));
        dir.setParentPath(rs.getString("parent_path"));
        dir.setCreatedAt(rs.getLong("created_at"));
        dir.setUpdatedAt(rs.getLong("updated_at"));
        return dir;
    }

    // ==================== 统计信息 ====================

    public KBInfo getInfo(String sessionId) {
        KBInfo info = new KBInfo(sessionId);
        try (Statement stmt = connection.createStatement()) {
            // 文档数量
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM documents")) {
                if (rs.next()) info.setTotalDocuments(rs.getInt(1));
            }
            // 分段数量
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM chunks")) {
                if (rs.next()) info.setTotalChunks(rs.getInt(1));
            }
            // 源文件总大小
            try (ResultSet rs = stmt.executeQuery("SELECT COALESCE(SUM(source_size), 0) FROM documents")) {
                if (rs.next()) info.setSourceTotalSize(rs.getLong(1));
            }
            // 文本总大小
            try (ResultSet rs = stmt.executeQuery("SELECT COALESCE(SUM(text_size), 0) FROM documents")) {
                if (rs.next()) info.setTextTotalSize(rs.getLong(1));
            }
        } catch (SQLException e) {
            log.warn("获取统计信息失败: {}", e.getMessage());
        }
        return info;
    }

    // ==================== Grep 搜索 ====================

    public List<GrepResult> grepKnowledgeTexts(String regex, int limit) {
        List<GrepResult> results = new ArrayList<>();
        String sql = "SELECT d.doc_id, d.file_name, d.file_path, kt.content " +
                "FROM knowledge_texts kt JOIN documents d ON kt.doc_id = d.doc_id";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    regex, java.util.regex.Pattern.CASE_INSENSITIVE);

            while (rs.next()) {
                String content = rs.getString("content");
                java.util.regex.Matcher matcher = pattern.matcher(content);

                if (matcher.find()) {
                    String docId = rs.getString("doc_id");
                    String fileName = rs.getString("file_name");
                    String filePath = rs.getString("file_path");

                    // 提取匹配的上下文
                    int start = Math.max(0, matcher.start() - 200);
                    int end = Math.min(content.length(), matcher.end() + 200);
                    String context = content.substring(start, end);

                    results.add(new GrepResult(docId, fileName, filePath, context,
                            matcher.start(), matcher.end()));

                    if (results.size() >= limit) break;
                }
            }
        } catch (SQLException e) {
            log.warn("Grep 搜索失败: {}", e.getMessage());
        }
        return results;
    }

    // ==================== 工具方法 ====================

    private byte[] serializeFloatArray(float[] array) {
        if (array == null) return null;
        ByteBuffer buffer = ByteBuffer.allocate(array.length * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (float f : array) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    // ==================== 内部类 ====================

    public static class VectorMatch {
        private final String chunkId;
        private final String docId;
        private final int chunkIndex;
        private final double score;
        private final String contentPreview;

        public VectorMatch(String chunkId, String docId, int chunkIndex,
                           double score, String contentPreview) {
            this.chunkId = chunkId;
            this.docId = docId;
            this.chunkIndex = chunkIndex;
            this.score = score;
            this.contentPreview = contentPreview;
        }

        public String getChunkId() { return chunkId; }
        public String getDocId() { return docId; }
        public int getChunkIndex() { return chunkIndex; }
        public double getScore() { return score; }
        public String getContentPreview() { return contentPreview; }
    }

    public static class GrepResult {
        private final String docId;
        private final String fileName;
        private final String filePath;
        private final String context;
        private final int matchStart;
        private final int matchEnd;

        public GrepResult(String docId, String fileName, String filePath,
                          String context, int matchStart, int matchEnd) {
            this.docId = docId;
            this.fileName = fileName;
            this.filePath = filePath;
            this.context = context;
            this.matchStart = matchStart;
            this.matchEnd = matchEnd;
        }

        public String getDocId() { return docId; }
        public String getFileName() { return fileName; }
        public String getFilePath() { return filePath; }
        public String getContext() { return context; }
        public int getMatchStart() { return matchStart; }
        public int getMatchEnd() { return matchEnd; }
    }
}
