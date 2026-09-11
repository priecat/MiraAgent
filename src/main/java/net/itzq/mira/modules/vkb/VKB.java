package net.itzq.mira.modules.vkb;

import net.itzq.mira.modules.vkb.model.Document;
import net.itzq.mira.modules.vkb.model.Directory;
import net.itzq.mira.modules.vkb.model.KBInfo;
import net.itzq.mira.modules.vkb.model.SearchResult;
import net.itzq.mira.modules.vkb.storage.SQLiteStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.FileSystem;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Stream;

/**
 * VKB
 *
 * 在 VFS 基础上额外提供：持久化存储、文档索引、全文搜索、向量检索。
 *
 * <pre>{@code
 * // 1. 全局初始化（应用启动时调用一次）
 * VKBConfig config = new VKBConfig();
 * config.setDataDir("/path/to/data");
 * VKB.init(config);
 *
 * // 2. 创建/加载知识库（替代 VFS.create()）
 * try (VKB vk = VKB.load("abcdef1234567890abcdef1234567890")) {
 *
 *     // VFS 兼容操作 —— 与 VFS 用法完全一致
 *     vk.write("/data/hello.txt", "Hello VKB");
 *     String content = vk.readString("/data/hello.txt");
 *     System.out.println(vk.listTree());
 *
 *     // VKB 额外能力 —— 知识库搜索
 *     List<SearchResult> results = vk.search("关键词", 5);
 * }
 * }</pre>
 *
 * @author tangzq
 */
public class VKB implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(VKB.class);

    private static volatile VKBConfig globalConfig;

    private final SessionKB kb;

    // ==================== 构造与生命周期 ====================

    private VKB(SessionKB kb) {
        this.kb = kb;
    }

    /**
     * 全局初始化（必须在使用前调用一次）
     */
    public static void init(VKBConfig config) {
        if (globalConfig != null) {
            log.warn("VKB 已初始化，忽略重复调用");
            return;
        }
        if (config == null) {
            throw new IllegalArgumentException("config 不能为 null");
        }
        if (config.getDataDir() == null || config.getDataDir().isEmpty()) {
            log.warn("未配置 dataDir，使用默认值: {}", VKBConstants.DEFAULT_DATA_DIR);
            config.setDataDir(VKBConstants.DEFAULT_DATA_DIR);
        }
        globalConfig = config;
        log.info("VKB 初始化完成，数据目录: {}", config.getDataDir());
    }

    /**
     * 获取全局配置
     */
    public static VKBConfig getConfig() {
        if (globalConfig == null) {
            throw new IllegalStateException("VKB 未初始化，请先调用 VKB.init(config)");
        }
        return globalConfig;
    }

    /**
     * 加载或创建会话知识库
     *
     * @param sessionId 32位UUID（无连字符）
     * @return VKB 实例（实现了 Closeable，推荐 try-with-resources）
     */
    public static VKB load(String sessionId) {
        if (globalConfig == null) {
            throw new IllegalStateException("VKB 未初始化，请先调用 VKB.init(config)");
        }
        validateSessionId(sessionId);
        try {
            SessionKB kb = new SessionKB(sessionId, globalConfig);
            return new VKB(kb);
        } catch (Exception e) {
            throw new RuntimeException("加载知识库失败: " + sessionId, e);
        }
    }

    /**
     * 关闭并释放资源（含 SQLite、Lucene、虚拟文件系统）
     */
    @Override
    public void close() throws IOException {
        if (kb != null) {
            kb.close();
        }
    }

    /**
     * 获取底层 SessionKB 实例，用于访问高级功能（addDocument、search、grep 等）。
     */
    public SessionKB getKB() {
        return kb;
    }

    // ==================== 2. 文件读写 ====================

    /**
     * 将字节数组写入指定路径的文件，若路径上已有文件则覆盖。
     * 写入时自动创建父目录。
     *
     * @param path 文件路径，如 "/data/hello.txt"
     * @param data 要写入的字节内容
     */
    public void write(String path, byte[] data) throws IOException {
        String np = normalizePath(path);
        String fileName = extractFileName(np);
        String parentDir = extractParentDir(np);

        // 自动创建父目录
        if (!parentDir.equals("/")) {
            getStorage().createDirectory(parentDir.endsWith("/") ? parentDir : parentDir + "/");
        }

        // 覆盖已有文件
        Document existing = getStorage().getDocumentByPath(np);
        if (existing != null) {
            kb.deleteDocument(existing.getDocId());
        }

        // 解码为文本用于知识库索引
        String textContent;
        try {
            textContent = new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            textContent = "";
        }

        kb.addDocument(fileName, data, parentDir, Collections.singletonList(textContent));
    }

    /**
     * 将字符串以 UTF-8 编码写入文件。
     *
     * @param path    文件路径
     * @param content 字符串内容
     */
    public void write(String path, String content) throws IOException {
        write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 将字符串以指定编码写入文件。
     *
     * @param path    文件路径
     * @param content 字符串内容
     * @param charset 字符编码
     */
    public void write(String path, String content, Charset charset) throws IOException {
        write(path, content.getBytes(charset));
    }

    /**
     * 读取文件全部字节。
     *
     * @param path 文件路径
     * @return 文件内容的字节数组
     * @throws NoSuchFileException 文件不存在时抛出
     */
    public byte[] readAllBytes(String path) throws IOException {
        String np = normalizePath(path);
        byte[] data = kb.getSourceFileByPath(np);
        if (data == null) {
            throw new NoSuchFileException(np);
        }
        return data;
    }

    /**
     * 以 UTF-8 编码读取文件内容为字符串。
     *
     * @param path 文件路径
     * @return 文件内容字符串
     * @throws NoSuchFileException 文件不存在时抛出
     */
    public String readString(String path) throws IOException {
        String np = normalizePath(path);
        String content = kb.getKnowledgeTextByPath(np);
        if (content == null) {
            throw new NoSuchFileException(np);
        }
        return content;
    }

    /**
     * 以指定编码读取文件内容为字符串。
     *
     * @param path    文件路径
     * @param charset 字符编码
     * @return 文件内容字符串
     */
    public String readString(String path, Charset charset) throws IOException {
        return new String(readAllBytes(path), charset);
    }

    // ==================== 3. 目录与文件信息 ====================

    /**
     * 递归列出知识库中所有文件和目录的树状结构。
     * 合并 directories 表的显式目录和 documents 表的文件。
     *
     * @return 以换行分隔的目录树字符串（目录以 '/' 结尾）
     */
    public String listTree() throws IOException {
        TreeSet<String> allPaths = new TreeSet<>();
        allPaths.add("/");

        // 从 directories 表收集所有目录
        for (Directory dir : getStorage().listSubDirectories("/")) {
            collectAllPaths(dir, allPaths);
        }

        // 从 documents 表收集所有文件
        List<Document> docs = kb.listDocuments();
        for (Document doc : docs) {
            String filePath = doc.getFilePath();
            allPaths.add(filePath);
            // 补充文件路径中的隐式目录
            int lastSlash = filePath.lastIndexOf('/');
            while (lastSlash > 0) {
                String dir = filePath.substring(0, lastSlash);
                allPaths.add(dir + "/");
                lastSlash = dir.lastIndexOf('/');
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String p : allPaths) {
            sb.append(p).append("\n");
        }
        return sb.toString();
    }

    /**
     * 递归收集目录及其所有子目录路径
     */
    private void collectAllPaths(Directory dir, TreeSet<String> allPaths) {
        allPaths.add(dir.getDirPath());
        for (Directory sub : getStorage().listSubDirectories(dir.getDirPath())) {
            collectAllPaths(sub, allPaths);
        }
    }

    /**
     * 列出指定目录下的直接子项（文件/目录）名称，不递归。
     * <p>
     * 返回的是每个子项的文件名（如 "hello.txt", "subdir"），不是完整路径。
     * 调用方拼接路径时应使用 dir + "/" + name。
     *
     * @param dir 目录路径
     * @return 包含子项文件名的流
     * @throws java.nio.file.NotDirectoryException 路径不是目录时抛出
     */
    public Stream<String> list(String dir) throws IOException {
        String np = normalizePath(dir);
        if (!isDirectory(np)) {
            throw new java.nio.file.NotDirectoryException(dir);
        }
        SQLiteStorage storage = getStorage();
        List<String> entries = storage.listDirectory(np);
        return entries.stream().map(e -> e.endsWith("/") ? e.substring(0, e.length() - 1) : e);
    }

    /**
     * 判断文件或目录是否存在。
     */
    public boolean exists(String path) {
        return getStorage().existsPath(normalizePath(path));
    }

    /**
     * 判断路径是否为目录。
     */
    public boolean isDirectory(String path) {
        return getStorage().isDirectory(normalizePath(path));
    }

    /**
     * 判断路径是否为普通文件。
     */
    public boolean isRegularFile(String path) {
        return getStorage().getDocumentByPath(normalizePath(path)) != null;
    }

    /**
     * 获取文件大小（字节数）。
     */
    public long size(String path) throws IOException {
        String np = normalizePath(path);
        Document doc = getStorage().getDocumentByPath(np);
        if (doc == null) {
            throw new NoSuchFileException(np);
        }
        return doc.getSourceSize();
    }

    // ==================== 4. 文件/目录操作 ====================

    /**
     * 创建目录（含所有不存在的父目录），类似 Files.createDirectories。
     * 在 VKB 中目录会被持久化到 directories 表。
     *
     * @param dir 目录路径
     */
    public void createDirectories(String dir) throws IOException {
        getStorage().createDirectory(normalizePath(dir));
    }

    /**
     * 创建单个目录（不创建父目录），类似 Files.createDirectory。
     *
     * @param dir 目录路径
     */
    public void createDirectory(String dir) throws IOException {
        String np = normalizePath(dir);
        // 检查父目录是否存在
        String parent = extractParentDir(np + "/");
        if (!parent.equals("/") && !getStorage().isDirectory(parent)) {
            throw new java.nio.file.NoSuchFileException("父目录不存在: " + parent);
        }
        getStorage().createDirectory(np + "/");
    }

    /**
     * 删除文件或空目录。
     * 如果路径是文件，删除该文件；如果是目录且为空，删除目录记录。
     *
     * @param path 文件或目录路径
     * @throws NoSuchFileException 路径不存在时抛出
     * @throws IllegalStateException 目录不为空时抛出
     */
    public void delete(String path) throws IOException {
        String np = normalizePath(path);

        // 先尝试作为文件删除
        Document doc = getStorage().getDocumentByPath(np);
        if (doc != null) {
            kb.deleteDocument(doc.getDocId());
            return;
        }

        // 再尝试作为目录删除（非递归，目录必须为空）
        String dirPath = np.endsWith("/") ? np : np + "/";
        if (getStorage().isDirectory(dirPath)) {
            try {
                getStorage().deleteDirectory(dirPath, false);
            } catch (SQLException e) {
                throw new IOException("删除目录失败: " + e.getMessage(), e);
            }
            return;
        }

        throw new NoSuchFileException(np);
    }

    /**
     * 如果存在则删除文件或空目录。
     *
     * @return 是否确实删除了
     */
    public boolean deleteIfExists(String path) throws IOException {
        String np = normalizePath(path);

        // 文件
        Document doc = getStorage().getDocumentByPath(np);
        if (doc != null) {
            kb.deleteDocument(doc.getDocId());
            return true;
        }

        // 目录
        String dirPath = np.endsWith("/") ? np : np + "/";
        if (getStorage().isDirectory(dirPath)) {
            try {
                getStorage().deleteDirectory(dirPath, false);
                return true;
            } catch (SQLException e) {
                throw new IOException("删除目录失败: " + e.getMessage(), e);
            }
        }
        return false;
    }

    /**
     * 递归删除目录及其下所有文件和子目录。
     * 如果路径指向文件，则删除该文件。
     *
     * @param path 目录或文件路径
     */
    public void deleteRecursively(String path) throws IOException {
        String np = normalizePath(path);

        // 文件：直接删除
        Document doc = getStorage().getDocumentByPath(np);
        if (doc != null) {
            kb.deleteDocument(doc.getDocId());
            return;
        }

        // 目录：递归删除
        String dirPath = np.endsWith("/") ? np : np + "/";
        if (getStorage().isDirectory(dirPath)) {
            try {
                getStorage().deleteDirectory(dirPath, true);
            } catch (SQLException e) {
                throw new IOException("递归删除目录失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 复制文件到目标路径。
     *
     * @param source  源文件路径
     * @param target  目标文件路径
     * @param options 可选的复制选项，支持 {@link StandardCopyOption#REPLACE_EXISTING}
     * @throws NoSuchFileException                     源文件不存在时抛出
     * @throws java.nio.file.FileAlreadyExistsException 目标已存在且未指定 REPLACE_EXISTING 时抛出
     */
    public void copy(String source, String target, CopyOption... options) throws IOException {
        String ns = normalizePath(source);
        String nt = normalizePath(target);

        byte[] data = readAllBytes(ns);
        String text = readString(ns);

        boolean replace = false;
        for (CopyOption opt : options) {
            if (opt == StandardCopyOption.REPLACE_EXISTING) {
                replace = true;
            }
        }

        Document existingTarget = getStorage().getDocumentByPath(nt);
        if (existingTarget != null) {
            if (!replace) {
                throw new java.nio.file.FileAlreadyExistsException(nt);
            }
            kb.deleteDocument(existingTarget.getDocId());
        }

        kb.addDocument(extractFileName(nt), data, extractParentDir(nt), Collections.singletonList(text));
    }

    /**
     * 移动/重命名文件。
     *
     * @param source  源文件路径
     * @param target  目标文件路径
     * @param options 可选的移动选项，支持 {@link StandardCopyOption#REPLACE_EXISTING}
     */
    public void move(String source, String target, CopyOption... options) throws IOException {
        // 如果源是目录，走目录重命名逻辑
        String ns = normalizePath(source);
        String sourceDir = ns.endsWith("/") ? ns : ns + "/";
        if (getStorage().isDirectory(sourceDir) && getStorage().getDocumentByPath(ns) == null) {
            // 源是目录
            String nt = normalizePath(target);
            String targetDir = nt.endsWith("/") ? nt : nt + "/";
            try {
                getStorage().renameDirectory(sourceDir, targetDir);
            } catch (SQLException e) {
                throw new IOException("移动目录失败: " + e.getMessage(), e);
            }
            return;
        }
        // 源是文件，走文件复制+删除
        copy(source, target, options);
        delete(source);
    }

    // ==================== 4b. 目录操作（网盘式文件夹） ====================

    /**
     * 创建目录（含所有不存在的父目录）。
     *
     * @param dirPath 目录路径，如 /docs/reports/
     */
    public void mkdirs(String dirPath) throws IOException {
        getStorage().createDirectory(normalizePath(dirPath));
    }

    /**
     * 重命名目录，级联更新所有子目录和子文件的路径。
     *
     * @param oldPath 原目录路径，如 /docs/old/
     * @param newPath 新目录路径，如 /docs/new/
     */
    public void renameDirectory(String oldPath, String newPath) throws IOException {
        try {
            getStorage().renameDirectory(
                    normalizePath(oldPath) + "/",
                    normalizePath(newPath) + "/");
        } catch (SQLException e) {
            throw new IOException("重命名目录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 移动目录到新位置，级联更新所有子目录和子文件路径。
     *
     * @param sourceDir 源目录路径
     * @param targetDir 目标目录路径
     */
    public void moveDirectory(String sourceDir, String targetDir) throws IOException {
        renameDirectory(sourceDir, targetDir);
    }

    /**
     * 删除目录（可选择是否递归删除内容）。
     *
     * @param dirPath   目录路径
     * @param recursive true 递归删除子目录和文件；false 仅删除空目录
     */
    public void deleteDirectory(String dirPath, boolean recursive) throws IOException {
        String np = normalizePath(dirPath);
        try {
            getStorage().deleteDirectory(np.endsWith("/") ? np : np + "/", recursive);
        } catch (SQLException e) {
            throw new IOException("删除目录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 列出目录下的直接子目录（不含文件）。
     *
     * @param dirPath 目录路径
     * @return 子目录列表
     */
    public List<Directory> listDirectories(String dirPath) {
        return getStorage().listSubDirectories(normalizePath(dirPath) + "/");
    }

    /**
     * 获取目录信息。
     *
     * @param dirPath 目录路径
     * @return Directory 对象，不存在返回 null
     */
    public Directory getDirectory(String dirPath) {
        return getStorage().getDirectory(normalizePath(dirPath) + "/");
    }

    // ==================== 5. VKB 扩展能力（知识库特有） ====================

    /**
     * 添加文档（支持分段）—— VKB 特有能力，VFS 不具备
     *
     * @param fileName    原始文件名（如 report.pdf）
     * @param sourceBytes 源文件字节（可为 null）
     * @param filePath    虚拟文件系统路径（如 /docs/report.md）
     * @param textChunks  分段后的文本列表
     * @return 文档ID
     */
    public String addDocument(String fileName, byte[] sourceBytes, String filePath, List<String> textChunks) {
        return kb.addDocument(fileName, sourceBytes, filePath, textChunks);
    }

    /**
     * 搜索：返回相关文件列表 —— VKB 特有能力，VFS 不具备
     *
     * @param query      查询关键词
     * @param resultSize 最大返回数量
     * @return 相关文件列表
     */
    public List<SearchResult> search(String query, int resultSize) {
        return kb.search(query, resultSize);
    }

    /**
     * Grep 搜索（正则表达式）—— VKB 特有能力，VFS 不具备
     *
     * @param regex      正则表达式
     * @param resultSize 最大返回数量
     * @return 匹配结果
     */
    public List<SearchResult> grep(String regex, int resultSize) {
        return kb.grep(regex, resultSize);
    }

    /**
     * 获取虚拟文件系统（NIO FileSystem）
     */
    public FileSystem getFileSystem() {
        return kb.getFileSystem();
    }

    /**
     * 获取知识库统计信息
     */
    public KBInfo getInfo() {
        return kb.getInfo();
    }

    // ==================== 内部工具 ====================

    private SQLiteStorage getStorage() {
        return kb.getSqliteStorage();
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "/";
        }
        String normalized = path.replace("\\", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String extractFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private static String extractParentDir(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "/";
        }
        return path.substring(0, lastSlash);
    }

    private static void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.length() != 32) {
            throw new IllegalArgumentException("sessionId 必须是32位UUID（无连字符）");
        }
        if (!sessionId.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException("sessionId 必须是32位十六进制字符");
        }
    }
}
