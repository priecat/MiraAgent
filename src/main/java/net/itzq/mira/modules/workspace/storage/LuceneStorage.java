package net.itzq.mira.modules.workspace.storage;

import net.itzq.mira.modules.workspace.model.SearchResult;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Lucene 全文索引存储层
 *
 * <p>额外提供按路径前缀删除索引的能力，以支持目录重命名/移动时的索引同步。
 *
 * @author tangzq
 */
public class LuceneStorage implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LuceneStorage.class);

    private final String indexPath;
    private boolean initialized = false;

    private Directory indexDir;
    private Analyzer analyzer;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public LuceneStorage(String indexPath) {
        this.indexPath = indexPath;
        // 延迟初始化：构造时不再打开索引，等待首次索引/搜索时按需打开
        log.info("LuceneStorage 已创建（延迟初始化）: indexPath={}", indexPath);
    }

    /**
     * 确保索引已打开（幂等、线程安全）。首次索引文档时调用；
     * 若打开失败则保持未初始化，全文检索降级为不可用。
     */
    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        try {
            initIndex();
        } catch (Exception e) {
            log.warn("Lucene 索引初始化失败，全文检索将不可用: {}", e.getMessage());
        }
    }

    private void initIndex() throws IOException {
        java.io.File dir = new java.io.File(indexPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        this.indexDir = FSDirectory.open(Paths.get(indexPath));
        this.analyzer = new SmartChineseAnalyzer();

        initialized = true;
        log.info("Lucene 索引初始化完成: {}", indexPath);
    }

    /**
     * 索引文档
     */
    public void indexDocument(String docId, String filePath, String content) {
        ensureInitialized();
        if (!initialized) {
            log.debug("Lucene 未初始化，跳过索引");
            return;
        }

        lock.writeLock().lock();
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

            try (IndexWriter writer = new IndexWriter(indexDir, config)) {
                // 先删除同 docId 的旧索引，保证覆盖语义
                writer.deleteDocuments(new Term("doc_id", docId));

                Document doc = new Document();
                doc.add(new StringField("doc_id", docId, Field.Store.YES));
                doc.add(new StringField("file_path", filePath, Field.Store.YES));
                doc.add(new TextField("content", content, Field.Store.YES));

                writer.addDocument(doc);
                writer.commit();

                log.info("索引文档成功: docId={}, filePath={}, contentLen={}", docId, filePath, content.length());
            }
        } catch (IOException e) {
            log.error("索引文档失败: {}", e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 删除文档索引
     */
    public void deleteDocument(String docId) {
        if (!initialized) return;

        lock.writeLock().lock();
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

            try (IndexWriter writer = new IndexWriter(indexDir, config)) {
                writer.deleteDocuments(new Term("doc_id", docId));
                writer.commit();
                log.debug("删除索引: {}", docId);
            }
        } catch (IOException e) {
            log.warn("删除索引失败: {}", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 按 doc_id 前缀删除索引（用于目录级删除/重命名）。
     *
     * @param docIdPrefix 前缀，如 /docs/reports/
     */
    public void deleteByPrefix(String docIdPrefix) {
        if (!initialized) return;

        lock.writeLock().lock();
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

            try (IndexWriter writer = new IndexWriter(indexDir, config)) {
                // PrefixQuery 需作用于未分词字段，doc_id 为 StringField 恰好满足
                writer.deleteDocuments(new PrefixQuery(new Term("doc_id", docIdPrefix)));
                writer.commit();
                log.debug("按前缀删除索引: {}", docIdPrefix);
            }
        } catch (IOException e) {
            log.warn("按前缀删除索引失败: {}", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 检查索引是否有文档
     */
    private boolean indexHasDocs() {
        try {
            IndexReader reader = DirectoryReader.open(indexDir);
            int numDocs = reader.numDocs();
            reader.close();
            return numDocs > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 全文搜索
     */
    public List<SearchResult> search(String query, int limit) {
        List<SearchResult> results = new ArrayList<>();

        if (!initialized) {
            log.debug("Lucene 未初始化，跳过搜索");
            return results;
        }

        // 检查索引是否有文档
        if (!indexHasDocs()) {
            log.info("Lucene 索引为空，跳过搜索");
            return results;
        }

        lock.readLock().lock();
        try {
            // 每次搜索创建新的 reader
            IndexReader reader = DirectoryReader.open(indexDir);
            IndexSearcher searcher = new IndexSearcher(reader);

            // 构建查询：短语匹配 + 分词匹配
            BooleanQuery.Builder builder = new BooleanQuery.Builder();

            // 1. 短语查询（完整匹配）
            QueryParser parser = new QueryParser("content", analyzer);
            parser.setDefaultOperator(QueryParser.Operator.OR);
            try {
                Query phraseQuery = parser.parse(QueryParser.escape(query));
                builder.add(phraseQuery, BooleanClause.Occur.SHOULD);
            } catch (Exception e) {
                log.debug("短语查询解析失败: {}", e.getMessage());
            }

            // 2. 分词后的前缀匹配
            String[] tokens = query.split("[\\s,，。、；：！？!?]+");
            for (String token : tokens) {
                if (token.length() >= 2) {
                    try {
                        Query prefixQuery = new QueryParser("content", analyzer)
                                .parse(QueryParser.escape(token.trim()) + "*");
                        builder.add(prefixQuery, BooleanClause.Occur.SHOULD);
                    } catch (Exception e) {
                        log.debug("前缀查询解析失败: {}", e.getMessage());
                    }
                }
            }

            Query finalQuery = builder.build();
            log.debug("Lucene 查询: {}", finalQuery.toString());

            TopDocs topDocs = searcher.search(finalQuery, limit);

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
                String docId = doc.get("doc_id");
                String filePath = doc.get("file_path");
                float score = scoreDoc.score;

                results.add(new SearchResult(docId, filePath, filePath, score, "lucene"));
            }

            reader.close();

            log.info("Lucene 搜索完成: query={}, 索引文档数={}, 结果数={}", query, topDocs.totalHits.value, results.size());

        } catch (Exception e) {
            log.error("Lucene 搜索失败: {}", e.getMessage(), e);
        } finally {
            lock.readLock().unlock();
        }

        return results;
    }

    /**
     * 清空索引
     */
    public void clearIndex() {
        if (!initialized) return;

        lock.writeLock().lock();
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

            try (IndexWriter writer = new IndexWriter(indexDir, config)) {
                writer.deleteAll();
                writer.commit();
                log.info("Lucene 索引已清空");
            }
        } catch (IOException e) {
            log.warn("清空索引失败: {}", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 重建索引
     */
    public void rebuildIndex() {
        clearIndex();
        // 需要外部调用重新索引所有文档
    }

    @Override
    public void close() {
        if (indexDir != null) {
            try {
                indexDir.close();
            } catch (IOException ignored) {
            }
        }
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
