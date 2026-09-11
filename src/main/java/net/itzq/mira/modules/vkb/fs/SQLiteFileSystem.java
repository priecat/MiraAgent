package net.itzq.mira.modules.vkb.fs;

import net.itzq.mira.modules.vkb.storage.SQLiteStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.*;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

    /**
     * 获取路径匹配器，支持 glob 和 regex 语法
     */
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        int pos = syntaxAndPattern.indexOf(':');
        if (pos <= 0 || pos == syntaxAndPattern.length() - 1) {
            throw new IllegalArgumentException("syntaxAndPattern must be of the form 'syntax:pattern'");
        }

        String syntax = syntaxAndPattern.substring(0, pos);
        String pattern = syntaxAndPattern.substring(pos + 1);

        String regex;
        if (syntax.equalsIgnoreCase("glob")) {
            // 将 glob 表达式转换为正则表达式
            regex = globToRegex(pattern);
        } else if (syntax.equalsIgnoreCase("regex")) {
            // 直接使用正则表达式
            regex = pattern;
        } else {
            throw new UnsupportedOperationException("Syntax '" + syntax + "' not recognized");
        }

        try {
            return new SQLitePathMatcher(Pattern.compile(regex));
        } catch (PatternSyntaxException e) {
            throw new PatternSyntaxException("Invalid pattern: " + regex, regex, e.getIndex());
        }
    }

    /**
     * 将 Glob 模式转换为正则表达式
     * 兼容标准 glob 规则：
     * - * 匹配零个或多个字符，但不跨越目录边界 (/)
     * - ** 匹配零个或多个目录层级 (跨越 /)
     * - ? 匹配单个字符
     * - [abc] / [a-z] 匹配字符范围
     * - {a,b,c} 匹配多个选项
     */
    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i++);
            switch (c) {
                case '*':
                    if (i < glob.length() && glob.charAt(i) == '*') {
                        i++; // 消耗第二个 *
                        // 检查是否是 **/ 的组合
                        if (i < glob.length() && glob.charAt(i) == '/') {
                            // **/ 表示零个或多个目录层级，连同胞后的 / 一起变为可选
                            sb.append("(?:.*/)?");
                            i++; // 消耗 /
                        } else {
                            // 单独的 ** 匹配任意字符，包括路径分隔符
                            sb.append(".*");
                        }
                    } else {
                        // 单个 * 匹配除路径分隔符外的任意字符
                        sb.append("[^/]*");
                    }
                    break;
                case '?':
                    sb.append("[^/]");
                    break;
                case '.':
                case '(':
                case ')':
                case '+':
                case '|':
                case '^':
                case '$':
                case '@':
                case '%':
                    sb.append("\\").append(c);
                    break;
                case '\\':
                    if (i == glob.length()) {
                        throw new IllegalArgumentException("Invalid glob pattern: trailing \\");
                    }
                    sb.append("\\").append(glob.charAt(i++));
                    break;
                case '[':
                    sb.append('[');
                    if (i < glob.length() && glob.charAt(i) == '!') {
                        sb.append('^');
                        i++;
                    }
                    if (i < glob.length() && glob.charAt(i) == ']') {
                        sb.append("\\]");
                        i++;
                    }
                    while (i < glob.length() && glob.charAt(i) != ']') {
                        char ch = glob.charAt(i++);
                        if (ch == '\\') {
                            if (i < glob.length()) {
                                sb.append("\\").append(glob.charAt(i++));
                            }
                        } else {
                            sb.append(ch);
                        }
                    }
                    if (i == glob.length()) {
                        throw new IllegalArgumentException("Missing ] in glob pattern");
                    }
                    sb.append(']');
                    i++;
                    break;
                case '{':
                    sb.append("(?:");
                    int j = i;
                    while (j < glob.length() && glob.charAt(j) != '}') {
                        j++;
                    }
                    if (j == glob.length()) {
                        throw new IllegalArgumentException("Missing } in glob pattern");
                    }
                    String[] options = glob.substring(i, j).split(",", -1);
                    for (int k = 0; k < options.length; k++) {
                        if (k > 0) sb.append("|");
                        for (int m = 0; m < options[k].length(); m++) {
                            char oc = options[k].charAt(m);
                            if (oc == '*') {
                                if (m + 1 < options[k].length() && options[k].charAt(m + 1) == '*') {
                                    sb.append(".*");
                                    m++;
                                } else {
                                    sb.append("[^/]*");
                                }
                            } else if (oc == '?') {
                                sb.append("[^/]");
                            } else if (isRegexMeta(oc)) {
                                sb.append("\\").append(oc);
                            } else {
                                sb.append(oc);
                            }
                        }
                    }
                    sb.append(")");
                    i = j + 1;
                    break;
                default:
                    sb.append(c);
            }
        }
        sb.append("$");
        return sb.toString();
    }

    private static boolean isRegexMeta(char c) {
        return "\\^$.*+?()[]{}|".indexOf(c) != -1;
    }

    /**
     * 内部 PathMatcher 实现
     */
    private static final class SQLitePathMatcher implements PathMatcher {
        private final Pattern pattern;

        SQLitePathMatcher(Pattern pattern) {
            this.pattern = pattern;
        }

        @Override
        public boolean matches(Path path) {
            // 直接通过 path.toString() 匹配，因为 SQLitePath 已经规范化了路径字符串
            return pattern.matcher(path.toString()).matches();
        }
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


    public void writeFileContent(String path, byte[] data) throws IOException {
        // TODO
        //  因为设计特性，需要转换文本并且建立索引，直接修改源文件会导致索引不同步
        //  所以这边改为只读，对于修改文件，需要从顶层VKB 的API中修改，而不能使用
        //  Files.write(path, data);
        //  如需支持，只需要 和 write 源文件相同逻辑即可

        throw new NonWritableChannelException();
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
