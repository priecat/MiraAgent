package net.itzq.mira.modules.vkb.fs;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/**
 * SQLite 文件系统路径实现
 *
 * @author tangzq
 */
public class SQLitePath implements Path {

    private final SQLiteFileSystem fs;
    private final String path;

    public SQLitePath(SQLiteFileSystem fs, String path) {
        this.fs = fs;
        this.path = normalize(path);
    }

    // 私有构造函数，跳过 normalize，仅供内部使用
    private SQLitePath(SQLiteFileSystem fs, String path, boolean normalized) {
        this.fs = fs;
        this.path = path;
    }

    private String normalize(String path) {
        // 规范化路径：统一使用 /，去除尾部斜杠等
        String normalized = path.replace("\\", "/");
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized;
    }

    @Override
    public FileSystem getFileSystem() {
        return fs;
    }

    @Override
    public boolean isAbsolute() {
        return path.startsWith("/");
    }

    @Override
    public Path getRoot() {
        return isAbsolute() ? new SQLitePath(fs, "/") : null;
    }

    @Override
    public Path getFileName() {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == path.length() - 1) return this;
        return new SQLitePath(fs, path.substring(lastSlash + 1),true);
    }

    @Override
    public Path getParent() {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) return isAbsolute() ? getRoot() : null;
        return new SQLitePath(fs, path.substring(0, lastSlash));
    }

    @Override
    public int getNameCount() {
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        if (stripped.isEmpty()) return 0;
        return stripped.split("/").length;
    }

    @Override
    public Path getName(int index) {
        String[] parts = path.substring(path.startsWith("/") ? 1 : 0).split("/");
        if (index < 0 || index >= parts.length) {
            throw new IllegalArgumentException("Index: " + index + ", Count: " + parts.length);
        }
        return new SQLitePath(fs, parts[index],true);
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        String[] parts = path.substring(path.startsWith("/") ? 1 : 0).split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = beginIndex; i < endIndex; i++) {
            if (sb.length() > 0) sb.append("/");
            sb.append(parts[i]);
        }
        return new SQLitePath(fs, sb.toString());
    }

    @Override
    public boolean startsWith(Path other) {
        return path.startsWith(other.toString());
    }

    @Override
    public boolean startsWith(String other) {
        return path.startsWith(other);
    }

    @Override
    public boolean endsWith(Path other) {
        return path.endsWith(other.toString());
    }

    @Override
    public boolean endsWith(String other) {
        return path.endsWith(other);
    }

    @Override
    public Path normalize() {
        return this; // 已经在构造时规范化
    }

    @Override
    public Path resolve(Path other) {
        if (other.isAbsolute()) return other;
        String otherStr = other.toString();
        if (otherStr.isEmpty()) return this;
        return new SQLitePath(fs, path + "/" + otherStr);
    }

    @Override
    public Path resolve(String other) {
        return resolve(new SQLitePath(fs, other));
    }

    @Override
    public Path resolveSibling(Path other) {
        Path parent = getParent();
        return parent != null ? parent.resolve(other) : other;
    }

    @Override
    public Path resolveSibling(String other) {
        return resolveSibling(new SQLitePath(fs, other));
    }

    @Override
    public Path relativize(Path other) {
        String otherStr = other.toString();
        if (otherStr.startsWith(path)) {
            String relative = otherStr.substring(path.length());
            if (relative.startsWith("/")) relative = relative.substring(1);
            return new SQLitePath(fs, relative);
        }
        throw new IllegalArgumentException("Cannot relativize: " + path + " vs " + otherStr);
    }

    @Override
    public URI toUri() {
        return URI.create("sqlite:vkb:" + path);
    }

    @Override
    public Path toAbsolutePath() {
        return this; // 已经是绝对路径
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
        return this;
    }

    @Override
    public File toFile() {
        throw new UnsupportedOperationException("SQLitePath cannot be converted to File");
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events,
                              WatchEvent.Modifier... modifiers) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<Path> iterator() {
        List<Path> names = new ArrayList<>();
        for (int i = 0; i < getNameCount(); i++) {
            names.add(getName(i));
        }
        return names.iterator();
    }

    @Override
    public int compareTo(Path other) {
        return path.compareTo(other.toString());
    }

    @Override
    public String toString() {
        return path;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof SQLitePath) {
            return path.equals(((SQLitePath) other).path);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }
}
