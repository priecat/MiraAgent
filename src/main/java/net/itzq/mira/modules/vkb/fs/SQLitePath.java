package net.itzq.mira.modules.vkb.fs;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/**
 * SQLite 虚拟文件系统路径实现。
 * <p>
 * 路径规则：
 * <ul>
 *   <li>以 "/" 开头为绝对路径，否则为相对路径</li>
 *   <li>根路径为 "/"</li>
 *   <li>路径分隔符统一为 "/"</li>
 *   <li>构造时即完成规范化：合并冗余斜杠、处理 "." 与 ".."、去除尾部斜杠</li>
 * </ul>
 *
 * @author tangzq
 */
public class SQLitePath implements Path {

    private final SQLiteFileSystem fs;
    /** 已规范化的路径字符串 */
    private final String path;

    public SQLitePath(SQLiteFileSystem fs, String path) {
        this.fs = fs;
        this.path = normalize(path);
    }

    /** 内部构造函数，跳过 normalize，仅供已规范化路径使用 */
    private SQLitePath(SQLiteFileSystem fs, String path, boolean normalized) {
        this.fs = fs;
        this.path = path;
    }

    // ==================== 规范化 ====================

    /**
     * 规范化路径字符串：
     * <ul>
     *   <li>反斜杠转正斜杠</li>
     *   <li>合并连续斜杠</li>
     *   <li>处理 "." 与 ".." 段（绝对路径下根目录之上的 ".." 丢弃；相对路径下保留前导 ".."）</li>
     *   <li>去除尾部斜杠（根路径 "/" 除外）</li>
     *   <li>保留是否为绝对路径的特性</li>
     * </ul>
     */
    private static String normalize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String p = input.replace("\\", "/");
        boolean absolute = p.startsWith("/");

        String[] parts = p.split("/");
        Deque<String> stack = new ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty() && !"..".equals(stack.peekLast())) {
                    stack.pollLast();
                } else if (!absolute) {
                    // 相对路径下保留前导 ".."
                    stack.addLast("..");
                }
                // 绝对路径下根目录的 ".." 直接丢弃
            } else {
                stack.addLast(part);
            }
        }

        StringBuilder sb = new StringBuilder();
        if (absolute) {
            sb.append("/");
        }
        boolean first = true;
        for (String s : stack) {
            if (!first) sb.append("/");
            sb.append(s);
            first = false;
        }
        return sb.toString();
    }

    // ==================== 基础信息 ====================

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
        return isAbsolute() ? new SQLitePath(fs, "/", true) : null;
    }

    // ==================== 路径组件 ====================

    @Override
    public Path getFileName() {
        if (path.isEmpty() || path.equals("/")) {
            return null;
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) {
            // 相对路径且无斜杠，整个即文件名
            return new SQLitePath(fs, path, true);
        }
        String name = path.substring(lastSlash + 1);
        if (name.isEmpty()) {
            return null;
        }
        return new SQLitePath(fs, name, true);
    }

    @Override
    public Path getParent() {
        if (path.isEmpty() || path.equals("/")) {
            return null;
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) {
            // 相对单段路径，无父级
            return null;
        }
        if (lastSlash == 0) {
            // 绝对路径首段，父级为根
            return new SQLitePath(fs, "/", true);
        }
        return new SQLitePath(fs, path.substring(0, lastSlash), true);
    }

    @Override
    public int getNameCount() {
        return splitParts(path).length;
    }

    @Override
    public Path getName(int index) {
        String[] parts = splitParts(path);
        if (index < 0 || index >= parts.length) {
            throw new IllegalArgumentException("Index: " + index + ", Count: " + parts.length);
        }
        return new SQLitePath(fs, parts[index], true);
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        String[] parts = splitParts(path);
        if (beginIndex < 0 || endIndex > parts.length || beginIndex >= endIndex) {
            throw new IllegalArgumentException(
                    "beginIndex: " + beginIndex + ", endIndex: " + endIndex + ", count: " + parts.length);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = beginIndex; i < endIndex; i++) {
            if (sb.length() > 0) sb.append("/");
            sb.append(parts[i]);
        }
        return new SQLitePath(fs, sb.toString(), true);
    }

    /** 将路径拆分为名称组件数组（去除根标记，仅返回名称部分） */
    private String[] splitParts(String p) {
        if (p == null || p.isEmpty() || p.equals("/")) {
            return new String[0];
        }
        String stripped = p.startsWith("/") ? p.substring(1) : p;
        if (stripped.isEmpty()) {
            return new String[0];
        }
        return stripped.split("/");
    }

    // ==================== 比较 ====================

    @Override
    public boolean startsWith(Path other) {
        SQLitePath o = asSQLitePath(other);
        if (o == null) {
            return false;
        }
        // 绝对/相对必须一致
        if (this.isAbsolute() != o.isAbsolute()) {
            return false;
        }
        String[] thisParts = splitParts(this.path);
        String[] otherParts = splitParts(o.path);
        if (otherParts.length > thisParts.length) {
            return false;
        }
        for (int i = 0; i < otherParts.length; i++) {
            if (!thisParts[i].equals(otherParts[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean startsWith(String other) {
        return startsWith(new SQLitePath(fs, other));
    }

    @Override
    public boolean endsWith(Path other) {
        SQLitePath o = asSQLitePath(other);
        if (o == null) {
            return false;
        }
        String[] thisParts = splitParts(this.path);
        String[] otherParts = splitParts(o.path);
        if (otherParts.length > thisParts.length) {
            return false;
        }
        // 若 other 为绝对路径，则 this 也必须是绝对路径且段数完全相同
        if (o.isAbsolute()) {
            if (!this.isAbsolute()) {
                return false;
            }
            if (otherParts.length != thisParts.length) {
                return false;
            }
        }
        int offset = thisParts.length - otherParts.length;
        for (int i = 0; i < otherParts.length; i++) {
            if (!thisParts[offset + i].equals(otherParts[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean endsWith(String other) {
        return endsWith(new SQLitePath(fs, other));
    }

    /** 将任意 Path 转为同文件系统的 SQLitePath；不同 fs 时基于字符串转换 */
    private SQLitePath asSQLitePath(Path other) {
        if (other == null) {
            return null;
        }
        if (other instanceof SQLitePath) {
            SQLitePath sp = (SQLitePath) other;
            return sp.fs == this.fs ? sp : new SQLitePath(this.fs, other.toString());
        }
        return new SQLitePath(this.fs, other.toString());
    }

    // ==================== 规范化 / 解析 ====================

    @Override
    public Path normalize() {
        return this; // 构造时已规范化
    }

    @Override
    public Path resolve(Path other) {
        SQLitePath o = asSQLitePath(other);
        if (o == null) {
            return this;
        }
        if (o.isAbsolute()) {
            return o;
        }
        if (o.path.isEmpty()) {
            return this;
        }
        if (this.path.equals("/")) {
            return new SQLitePath(fs, "/" + o.path, true);
        }
        if (this.path.isEmpty()) {
            return new SQLitePath(fs, o.path, true);
        }
        return new SQLitePath(fs, this.path + "/" + o.path);
    }

    @Override
    public Path resolve(String other) {
        return resolve(new SQLitePath(fs, other));
    }

    @Override
    public Path resolveSibling(Path other) {
        Objects.requireNonNull(other);
        Path parent = getParent();
        return (parent == null) ? other : parent.resolve(other);
    }

    @Override
    public Path resolveSibling(String other) {
        return resolveSibling(new SQLitePath(fs, other));
    }

    @Override
    public Path relativize(Path other) {
        SQLitePath o = asSQLitePath(other);
        if (o == null) {
            throw new IllegalArgumentException("Cannot relativize against path from different filesystem");
        }
        if (this.isAbsolute() != o.isAbsolute()) {
            throw new IllegalArgumentException("'other' is different type of path");
        }
        String[] thisParts = splitParts(this.path);
        String[] otherParts = splitParts(o.path);

        int common = 0;
        while (common < thisParts.length && common < otherParts.length
                && thisParts[common].equals(otherParts[common])) {
            common++;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = common; i < thisParts.length; i++) {
            if (sb.length() > 0) sb.append("/");
            sb.append("..");
        }
        for (int i = common; i < otherParts.length; i++) {
            if (sb.length() > 0) sb.append("/");
            sb.append(otherParts[i]);
        }
        return new SQLitePath(fs, sb.toString(), true);
    }

    // ==================== 转换 ====================

    @Override
    public URI toUri() {
        // URI 总是绝对形式，相对路径补 "/"
        String abs = isAbsolute() ? path : "/" + path;
        return URI.create("sqlite:vkb:" + abs);
    }

    @Override
    public Path toAbsolutePath() {
        if (isAbsolute()) {
            return this;
        }
        // 相对路径默认相对根目录解析
        return new SQLitePath(fs, "/" + path, true);
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
        return toAbsolutePath();
    }

    @Override
    public File toFile() {
        throw new UnsupportedOperationException("SQLitePath cannot be converted to File");
    }

    // ==================== 监听 ====================

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events,
            WatchEvent.Modifier... modifiers) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) throws IOException {
        throw new UnsupportedOperationException();
    }

    // ==================== 迭代 / 比较 ====================

    @Override
    public Iterator<Path> iterator() {
        String[] parts = splitParts(path);
        List<Path> names = new ArrayList<>(parts.length);
        for (String p : parts) {
            names.add(new SQLitePath(fs, p, true));
        }
        return names.iterator();
    }

    @Override
    public int compareTo(Path other) {
        SQLitePath o = asSQLitePath(other);
        if (o == null) {
            return 1;
        }
        return this.path.compareTo(o.path);
    }

    // ==================== Object 方法 ====================

    @Override
    public String toString() {
        return path;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SQLitePath)) return false;
        SQLitePath o = (SQLitePath) other;
        return this.fs == o.fs && this.path.equals(o.path);
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(fs) + path.hashCode();
    }
}
