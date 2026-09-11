package net.itzq.mira.modules.vkb.fs;

import net.itzq.mira.modules.vkb.storage.SQLiteStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;

/**
 * SQLite 文件系统实现
 *
 * 将 SQLite 数据库中的文档虚拟为文件系统，支持标准 NIO 操作
 *
 * @author tangzq
 */
public class SQLiteFileSystem extends FileSystem {

    private static final Logger log = LoggerFactory.getLogger(SQLiteFileSystem.class);

    private final SQLiteFileSystemProvider provider;
    private final SQLiteStorage storage;
    private volatile boolean closed = false;

    public SQLiteFileSystem(SQLiteFileSystemProvider provider, SQLiteStorage storage) {
        this.provider = provider;
        this.storage = storage;
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() throws IOException {
        closed = true;
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return Collections.singletonList(new SQLitePath(this, "/"));
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return Collections.singletonList(new SQLiteFileStore(this));
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return Collections.singleton("basic");
    }

    @Override
    public Path getPath(String first, String... more) {
        StringBuilder sb = new StringBuilder(first);
        for (String segment : more) {
            if (segment != null && !segment.isEmpty()) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '/') {
                    sb.append('/');
                }
                sb.append(segment);
            }
        }
        return new SQLitePath(this, sb.toString());
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        // 简化实现：只支持 glob 语法
        if (!syntaxAndPattern.startsWith("glob:")) {
            throw new UnsupportedOperationException("Only glob syntax is supported");
        }
        String pattern = syntaxAndPattern.substring(5);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        return path -> matcher.matches(path.getFileName());
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService() throws IOException {
        throw new UnsupportedOperationException();
    }

    // ==================== 内部文件操作 ====================

    /**
     * 读取文件内容
     */
    byte[] readFileContent(String path) throws IOException {
        String content = storage.getKnowledgeText(getDocIdByPath(path));
        if (content == null) {
            throw new NoSuchFileException(path);
        }
        return content.getBytes("UTF-8");
    }

    /**
     * 列出目录内容
     */
    List<String> listDirectory(String dirPath) throws IOException {
        return storage.listDirectory(dirPath);
    }

    /**
     * 检查路径是否存在
     */
    boolean exists(String path) {
        return storage.existsPath(path);
    }

    /**
     * 判断是否为目录
     */
    boolean isDirectory(String path) {
        return storage.isDirectory(path);
    }

    /**
     * 判断是否为文件
     */
    boolean isFile(String path) {
        return storage.getDocumentByPath(path) != null;
    }

    /**
     * 获取文件大小
     */
    long getFileSize(String path) throws IOException {
        net.itzq.mira.modules.vkb.model.Document doc = storage.getDocumentByPath(path);
        if (doc != null) {
            return doc.getTextSize();
        }
        throw new NoSuchFileException(path);
    }

    /**
     * 获取文件名
     */
    String getFileName(String path) throws IOException {
        net.itzq.mira.modules.vkb.model.Document doc = storage.getDocumentByPath(path);
        if (doc != null) {
            return doc.getFileName();
        }
        throw new NoSuchFileException(path);
    }

    /**
     * 获取 DocID
     */
    private String getDocIdByPath(String path) {
        net.itzq.mira.modules.vkb.model.Document doc = storage.getDocumentByPath(path);
        return doc != null ? doc.getDocId() : null;
    }

    /**
     * 获取存储层
     */
    SQLiteStorage getStorage() {
        return storage;
    }
}
