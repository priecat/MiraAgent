package net.itzq.mira.modules.vkb.fs;

import net.itzq.mira.modules.vkb.storage.SQLiteStorage;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;

/**
 * SQLite 文件系统提供者
 *
 * @author tangzq
 */
public class SQLiteFileSystemProvider extends FileSystemProvider {

    public static final String SCHEME = "sqlite-vkb";

    private final Map<String, SQLiteFileSystem> fileSystems = new HashMap<>();

    /**
     * 注册文件系统（非标准 SPI 方式，供内部使用）
     */
    public SQLiteFileSystem registerFileSystem(String sessionId, SQLiteStorage storage) {
        synchronized (fileSystems) {
            SQLiteFileSystem fs = new SQLiteFileSystem(this, storage);
            fileSystems.put(sessionId, fs);
            return fs;
        }
    }

    @Override
    public String getScheme() {
        return SCHEME;
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        throw new UnsupportedOperationException("Use registerFileSystem instead");
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        String sessionId = uri.getHost();
        synchronized (fileSystems) {
            return fileSystems.get(sessionId);
        }
    }

    @Override
    public Path getPath(URI uri) {
        String sessionId = uri.getHost();
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        SQLiteFileSystem fs;
        synchronized (fileSystems) {
            fs = fileSystems.get(sessionId);
        }
        if (fs == null) {
            throw new FileSystemNotFoundException("Session not found: " + sessionId);
        }
        return fs.getPath(path);
    }

    // ==================== 文件操作 ====================

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                               FileAttribute<?>... attrs) throws IOException {
        SQLitePath sqlitePath = toSQLitePath(path);
        return new SQLiteFileChannel(sqlitePath, options);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, Filter<? super Path> filter) throws IOException {
        SQLitePath sqlitePath = toSQLitePath(dir);
        return new SQLiteDirectoryStream(sqlitePath, filter);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        // SQLite 文件系统中目录是隐式的
    }

    @Override
    public void delete(Path path) throws IOException {
        SQLitePath sqlitePath = toSQLitePath(path);
        SQLiteFileSystem fs = toSQLiteFileSystem(sqlitePath.getFileSystem());
        net.itzq.mira.modules.vkb.model.Document doc = fs.getStorage()
                .getDocumentByPath(sqlitePath.toString());
        if (doc != null) {
            fs.getStorage().deleteDocument(doc.getDocId());
        } else {
            throw new NoSuchFileException(path.toString());
        }
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        throw new UnsupportedOperationException("Copy not supported");
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        throw new UnsupportedOperationException("Move not supported");
    }

    @Override
    public boolean isSameFile(Path path1, Path path2) throws IOException {
        return path1.equals(path2);
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
        return false;
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        SQLitePath sqlitePath = toSQLitePath(path);
        return new SQLiteFileStore(toSQLiteFileSystem(sqlitePath.getFileSystem()));
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        SQLitePath sqlitePath = toSQLitePath(path);
        SQLiteFileSystem fs = toSQLiteFileSystem(sqlitePath.getFileSystem());
        if (!fs.exists(sqlitePath.toString())) {
            throw new NoSuchFileException(path.toString());
        }
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
                                                                  LinkOption... options) {
        return null;
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
                                                             LinkOption... options) throws IOException {
        SQLitePath sqlitePath = toSQLitePath(path);
        return (A) new SQLiteFileAttributes(sqlitePath);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes,
                                               LinkOption... options) throws IOException {
        return Collections.emptyMap();
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value,
                              LinkOption... options) throws IOException {
        throw new UnsupportedOperationException();
    }

    // ==================== 辅助方法 ====================

    private SQLitePath toSQLitePath(Path path) {
        if (path instanceof SQLitePath) {
            return (SQLitePath) path;
        }
        throw new IllegalArgumentException("Not a SQLitePath: " + path.getClass());
    }

    private SQLiteFileSystem toSQLiteFileSystem(FileSystem fs) {
        if (fs instanceof SQLiteFileSystem) {
            return (SQLiteFileSystem) fs;
        }
        throw new IllegalArgumentException("Not a SQLiteFileSystem: " + fs.getClass());
    }
}
