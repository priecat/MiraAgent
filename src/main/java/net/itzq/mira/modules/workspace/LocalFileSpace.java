package net.itzq.mira.modules.workspace;

import net.itzq.mira.modules.vfs.model.SearchResult;
import net.itzq.mira.modules.workspace.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * LocalFileSpace -- 以真实磁盘目录为根的本地文件空间
 *
 * <p>与 {@link FileWorkspace} 的区别：FileWorkspace 的存储根固定在
 * 全局 dataDir 下按 sessionId 划分；LocalFileSpace 直接以用户指定的
 * 真实目录（如 D:\work\myproject）为根，所有接口均通过 NIO 直操真实文件，
 * 不落 Lucene 索引（search 返回空，grep 基于文件内容直接匹配）。
 *
 * <p>路径语义与 FileWorkspace 一致：接口内 path 使用以 / 分隔的虚拟路径
 * （如 "/data/hello.txt"），内部经 {@link FileStorage#toReal(String)}
 * 映射到真实磁盘路径，并自带 "../" 越界防护。
 *
 * <pre>{@code
 * // 打开以真实目录为根的本地文件空间（目录必须已存在）
 * try (LocalFileSpace wk = LocalFileSpace.open("D:\\work\\myproject")) {
 *     wk.writeString("/notes/a.txt", "hello");
 *     System.out.println(wk.listTree());
 * }
 * }</pre>
 *
 * @author tangzq
 */
public class LocalFileSpace implements Workspace {

    private static final Logger log = LoggerFactory.getLogger(LocalFileSpace.class);

    /** 真实根目录（绝对路径，已归一化） */
    private final String rootPath;

    private final Path root;

    /** 复用磁盘存储层：虚拟路径 <-> 真实路径映射 + 越界防护 + 基础文件操作 */
    private final FileStorage fileStorage;

    private LocalFileSpace(String rootPath) {
        this.rootPath = rootPath;
        this.root = Paths.get(rootPath).toAbsolutePath().normalize();
        // 不配置 mapDir：本地空间无映射概念，映射路径 == 真实路径
        this.fileStorage = new FileStorage(root.toString(), null);
    }

    /**
     * 打开以真实目录为根的本地文件空间（目录必须已存在，不自动创建）
     *
     * @param rootPath 真实目录绝对路径，如 D:\work\myproject
     */
    public static LocalFileSpace open(String rootPath) {
        if (rootPath == null || rootPath.trim().isEmpty()) {
            throw new IllegalArgumentException("rootPath 不能为空");
        }
        Path p = Paths.get(rootPath.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException("本地工作空间路径不存在或不是目录: " + p);
        }
        return new LocalFileSpace(p.toString());
    }

    /**
     * 获取真实根目录（绝对路径，已归一化）
     */
    public String getRootPath() {
        return root.toString();
    }

    /**
     * 关闭并释放资源（LocalFileSpace 无需持有任何资源，空实现）
     */
    @Override
    public void close() {
        log.debug("LocalFileSpace 已关闭: root={}", rootPath);
    }

    // ==================== 1. 文件写入 ====================

    /**
     * 将字节数组写入指定路径的文件，若路径上已有文件则覆盖。
     * 写入时自动创建父目录。
     */
    @Override
    public void write(String path, byte[] data) throws IOException {
        fileStorage.writeFile(path, data);
    }

    /**
     * 将字符串以 UTF-8 编码写入文件。
     */
    @Override
    public void write(String path, String content) throws IOException {
        write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 将字符串以指定编码写入文件。
     */
    @Override
    public void write(String path, String content, Charset charset) throws IOException {
        write(path, content.getBytes(charset));
    }

    // ==================== 2. 文件读取 ====================

    /**
     * 读取文件全部字节。
     *
     * @throws NoSuchFileException 文件不存在时抛出
     */
    @Override
    public byte[] readAllBytes(String path) throws IOException {
        byte[] data = fileStorage.readFile(path);
        if (data == null) {
            throw new NoSuchFileException(FileStorage.normalizeVirtual(path));
        }
        return data;
    }

    /**
     * 以 UTF-8 编码读取文件内容为字符串。
     */
    @Override
    public String readString(String path) throws IOException {
        return new String(readAllBytes(path), StandardCharsets.UTF_8);
    }

    /**
     * 以指定编码读取文件内容为字符串。
     */
    @Override
    public String readString(String path, Charset charset) throws IOException {
        return new String(readAllBytes(path), charset);
    }

    // ==================== 3. 目录与文件信息 ====================

    /**
     * 递归列出所有文件和目录的树状结构（目录以 '/' 结尾）。
     */
    @Override
    public String listTree() throws IOException {
        TreeSet<String> allPaths = new TreeSet<>();
        allPaths.add("/");
        if (Files.isDirectory(root)) {
            List<Path> paths;
            try (Stream<Path> stream = Files.walk(root)) {
                paths = stream.filter(p -> !p.equals(root)).collect(java.util.stream.Collectors.toList());
            }
            for (Path p : paths) {
                String vp = fileStorage.toVirtual(p);
                allPaths.add(Files.isDirectory(p) ? vp + "/" : vp);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : allPaths) {
            sb.append(p).append("\n");
        }
        return sb.toString();
    }

    /**
     * 列出指定目录下的直接子项（文件/目录）名称，不递归。
     *
     * @throws NotDirectoryException 路径不是目录时抛出
     */
    @Override
    public Stream<String> list(String dir) throws IOException {
        String np = FileStorage.normalizeVirtual(dir);
        if (!isDirectory(np)) {
            throw new NotDirectoryException(dir);
        }
        List<String> entries = fileStorage.listDirectory(np);
        return entries.stream().map(e -> e.endsWith("/") ? e.substring(0, e.length() - 1) : e);
    }

    @Override
    public boolean exists(String path) {
        return fileStorage.existsPath(path);
    }

    @Override
    public boolean isDirectory(String path) {
        return fileStorage.isDirectory(path);
    }

    @Override
    public boolean isRegularFile(String path) {
        return fileStorage.isRegularFile(path);
    }

    /**
     * 获取文件大小（字节数）。
     *
     * @throws NoSuchFileException 文件不存在时抛出
     */
    @Override
    public long size(String path) throws IOException {
        String np = FileStorage.normalizeVirtual(path);
        if (!fileStorage.isRegularFile(np)) {
            throw new NoSuchFileException(np);
        }
        return Files.size(fileStorage.toReal(np));
    }

    // ==================== 4. 文件/目录操作 ====================

    /**
     * 创建目录（含所有不存在的父目录），类似 Files.createDirectories。
     */
    @Override
    public void createDirectories(String dir) throws IOException {
        fileStorage.createDirectory(dir);
    }

    /**
     * 删除文件或空目录。
     *
     * @throws NoSuchFileException 路径不存在时抛出
     */
    @Override
    public void delete(String path) throws IOException {
        String np = FileStorage.normalizeVirtual(path);
        if (fileStorage.deleteFile(np)) {
            return;
        }
        if (fileStorage.isDirectory(np)) {
            fileStorage.deleteDirectory(np, false);
            return;
        }
        throw new NoSuchFileException(np);
    }

    /**
     * 如果存在则删除文件或空目录。
     *
     * @return 是否确实删除了
     */
    @Override
    public boolean deleteIfExists(String path) throws IOException {
        String np = FileStorage.normalizeVirtual(path);
        if (fileStorage.deleteFile(np)) {
            return true;
        }
        if (fileStorage.isDirectory(np)) {
            fileStorage.deleteDirectory(np, false);
            return true;
        }
        return false;
    }

    /**
     * 递归删除目录及其下所有文件和子目录。如果路径指向文件，则删除该文件。
     */
    @Override
    public void deleteRecursively(String path) throws IOException {
        String np = FileStorage.normalizeVirtual(path);
        if (fileStorage.getDocumentByPath(np) != null) {
            fileStorage.deleteFile(np);
            return;
        }
        if (fileStorage.isDirectory(np)) {
            fileStorage.deleteDirectory(np, true);
        }
    }

    /**
     * 复制文件到目标路径（仅支持文件；目录请配合 list/deleteRecursively 自行处理）。
     *
     * @throws NoSuchFileException                      源文件不存在时抛出
     * @throws FileAlreadyExistsException               目标已存在且未指定 REPLACE_EXISTING 时抛出
     */
    @Override
    public void copy(String source, String target, CopyOption... options) throws IOException {
        byte[] data = readAllBytes(source);
        String nt = FileStorage.normalizeVirtual(target);
        boolean replace = Arrays.asList(options).contains(StandardCopyOption.REPLACE_EXISTING);
        if (fileStorage.existsPath(nt) && !replace) {
            throw new FileAlreadyExistsException(nt);
        }
        write(nt, data);
    }

    /**
     * 移动/重命名文件或目录。
     *
     * <p>目录：直接磁盘 Files.move（天然级联），并拒绝移动到自身子目录；
     * 文件：读 + 写 + 删（跨盘符安全）。
     *
     * @throws NoSuchFileException                      源路径不存在时抛出
     * @throws FileAlreadyExistsException               目标已存在且未指定 REPLACE_EXISTING 时抛出
     */
    @Override
    public void move(String source, String target, CopyOption... options) throws IOException {
        String ns = FileStorage.normalizeVirtual(source);
        String nt = FileStorage.normalizeVirtual(target);
        if (fileStorage.getDocumentByPath(ns) == null && fileStorage.isDirectory(ns)) {
            // 目录：磁盘直接移动
            if (ns.equals("/") || nt.equals("/")) {
                throw new IOException("根目录不允许移动/重命名");
            }
            Path src = fileStorage.toReal(ns);
            Path dst = fileStorage.toReal(nt);
            if (dst.startsWith(src)) {
                throw new IOException("不能将目录移动到其自身子目录下: " + target);
            }
            boolean replace = Arrays.asList(options).contains(StandardCopyOption.REPLACE_EXISTING);
            if (fileStorage.existsPath(nt)) {
                if (!replace) {
                    throw new FileAlreadyExistsException(nt);
                }
                deleteRecursively(nt);
            }
            Path parent = dst.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try {
                Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(src, dst);
            }
            return;
        }
        // 文件：读 + 写 + 删
        byte[] data = readAllBytes(ns);
        boolean replace = Arrays.asList(options).contains(StandardCopyOption.REPLACE_EXISTING);
        if (fileStorage.existsPath(nt) && !replace) {
            throw new FileAlreadyExistsException(nt);
        }
        write(nt, data);
        delete(ns);
    }

    // ==================== 5. 全文检索 ====================

    /**
     * 搜索：LocalFileSpace 不建 Lucene 索引，恒返回空结果。
     */
    @Override
    public List<SearchResult> search(String query, int resultSize) {
        log.warn("LocalFileSpace 未启用 Lucene 索引，search 返回空结果: query={}", query);
        return new ArrayList<>();
    }

    /**
     * Grep 搜索（正则表达式，直接遍历真实文件内容匹配）。
     */
    @Override
    public List<SearchResult> grep(String regex, int resultSize) {
        List<SearchResult> results = new ArrayList<>();
        for (FileStorage.GrepResult gr : fileStorage.grepFiles(regex, resultSize)) {
            SearchResult result = new SearchResult(gr.getDocId(), gr.getFileName(),
                    gr.getFilePath(), 1.0, "grep");
            result.setRealPath(gr.getRealPath());
            result.setMappedPath(gr.getMappedPath());
            results.add(result);
        }
        return results;
    }

    // ==================== 6. 生命周期 ====================

    /**
     * 获取底层文件系统（默认文件系统，即真实磁盘）。
     */
    @Override
    public FileSystem getFileSystem() {
        return FileSystems.getDefault();
    }

    /**
     * 本地空间打开时根目录必须已存在，恒返回 true。
     */
    @Override
    public boolean isInitialized() {
        return fileStorage.isInitialized();
    }
}
