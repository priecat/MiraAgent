package net.itzq.mira.modules.vfs;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.workspace.Workspace;
import net.itzq.mira.modules.workspace.WorkspaceConfig;
import net.itzq.mira.modules.vfs.model.SearchResult;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 基于 ZipFS 的虚拟文件系统。
 * <p>
 * 设计原则：<b>懒加载</b>
 * <ul>
 *     <li>{@link #load(String)} 不会创建任何文件，仅计算路径</li>
 *     <li>首次 <b>写入</b> 才真正创建 zip 文件并打开 FileSystem</li>
 *     <li>读取/查询操作在 zip 不存在时直接返回空值，不产生空文件</li>
 * </ul>
 */
@Slf4j
public class VFS implements Workspace {

    /** 兼容 facade 用的全局配置，由 {@link #init(WorkspaceConfig)} 设置（默认运行时路径） */
    private static volatile WorkspaceConfig globalConfig;

    /**
     * 本实例所属工作空间的配置（实例化）：由 {@code VfsService}（每个 KernelRuntime 一个）
     * 注入 —— 数据目录因此按运行时隔离；{@code createZip} 形态（无服务）为 null，回落全局配置。
     */
    private final WorkspaceConfig config;

    /** 会话 ID */
    private final String sessionId;

    /** 会话对应的 zip 文件路径（持久化在磁盘上） */
    private final Path zipFile;

    /** 懒加载的 FileSystem，写入首次发生时才创建 */
    private volatile FileSystem fs;

    /** 保护 fs 初始化 / 关闭的锁 */
    private final Object lock = new Object();

    // ==================== 全局初始化 ====================

    /**
     * 全局初始化（必须在使用前调用一次）
     */
    public static void init(WorkspaceConfig config) {
        if (globalConfig != null) {
            log.warn("VFS 已初始化，忽略重复调用");
            return;
        }
        if (config == null) {
            throw new IllegalArgumentException("config 不能为 null");
        }
        if (config.getDataDir() == null || config.getDataDir().isEmpty()) {
            throw new IllegalArgumentException("dataDir 不能为 null 或空");
        }
        globalConfig = config;
        log.info("VFS 初始化完成，数据目录: {}", config.getDataDir());
    }

    /**
     * 加载（或延迟创建）会话工作空间。
     * <p>
     * 本方法仅计算 zip 路径，<b>不会</b>真正创建文件，
     * 直到首次写入时才会按需创建。
     *
     * @param sessionId 32 位 UUID（无连字符）
     * @return VFS 实例（实现了 Closeable，推荐 try-with-resources）
     */
    public static VFS load(String sessionId) {
        // 兼容 facade：优先用静态 init 注入的配置（存量路径），
        // 未 init 时回落默认运行时的 VfsService（跟随声明里的工作空间配置）
        WorkspaceConfig cfg = globalConfig;
        if (cfg == null || cfg.getDataDir() == null || cfg.getDataDir().isEmpty()) {
            cfg = net.itzq.mira.modules.runtime.KernelRuntime.defaultRuntime().vfs().config();
        }
        if (cfg == null || cfg.getDataDir() == null || cfg.getDataDir().isEmpty()) {
            throw new IllegalStateException("VFS 未初始化，请先调用 VFS.init(config)");
        }
        return loadWith(cfg, sessionId);
    }

    /**
     * 按**指定配置**加载会话工作空间（实例化路径）：由 {@code VfsService} 调用，
     * 每个 KernelRuntime 用自己的 dataDir，实例间天然隔离。
     */
    static VFS loadWith(WorkspaceConfig config, String sessionId) {
        if (config == null) {
            throw new IllegalStateException("VFS 未初始化：配置为空");
        }
        if (config.getDataDir() == null || config.getDataDir().isEmpty()) {
            throw new IllegalStateException("VFS 未初始化：dataDir 为空");
        }
        validateSessionId(sessionId);
        return new VFS(sessionId, config);
    }

    /**
     * 构建会话 zip 路径: {@code <dataDir>/ab/cd/<sessionId>.zip}
     */
    private static String buildSessionZipPath(WorkspaceConfig config, String sessionId) {
        String a = sessionId.substring(0, 2);
        String b = sessionId.substring(2, 4);
        return config.getDataDir()
                + File.separator + a
                + File.separator + b
                + File.separator + sessionId + ".zip";
    }

    private VFS(String sessionId, WorkspaceConfig config) {
        this.sessionId = sessionId;
        this.config = config;
        this.zipFile = Paths.get(buildSessionZipPath(config, sessionId));
        log.debug("VFS 已创建（延迟初始化）: sessionId={}, zipFile={}", sessionId, zipFile);
    }

    /** 本实例生效的配置（会话实例持自身服务配置；createZip 形态回落全局配置） */
    private WorkspaceConfig effectiveConfig() {
        return config != null ? config : globalConfig;
    }

    // ==================== 1. 显式创建/打开（可选入口） ====================

    public static VFS createZip(Path zipFile) throws IOException {
        // 注意：此方法创建的 VFS 没有 sessionId
        // 如需完整功能，请使用 VFS.load(sessionId)
        return new VFS(null, zipFile, null);
    }

    /** 按指定配置创建 zip 形态会话（{@code VfsService.createZip} 用） */
    static VFS createZipWith(Path zipFile, WorkspaceConfig config) throws IOException {
        return new VFS(null, zipFile, config);
    }

    /**
     * 内部构造函数，用于 createZip 方法
     */
    private VFS(String sessionId, Path zipFile, WorkspaceConfig config) {
        this.sessionId = sessionId;
        this.config = config;
        this.zipFile = zipFile;
    }

    // ==================== 核心：懒加载 FileSystem ====================

    /**
     * 写入路径：必须得到一个非 null 的 FileSystem。
     * 如果 zip 不存在则创建；已存在则打开。
     */
    private FileSystem ensureOpenForWrite() throws IOException {
        FileSystem local = fs;
        if (local != null) {
            return local;
        }
        synchronized (lock) {
            if (fs != null) {
                return fs;
            }
            fs = openZip(true);
            log.debug("VFS 已实际创建/打开: {}", zipFile);
            return fs;
        }
    }

    /**
     * 读取路径：返回当前已打开的 FileSystem；
     * 如果尚未打开，但磁盘上已存在历史 zip，则打开它；
     * 如果磁盘上不存在（或为空文件），返回 null（不创建）。
     */
    private FileSystem getFsForRead() throws IOException {
        FileSystem local = fs;
        if (local != null) {
            return local;
        }
        // 快速失败：磁盘上没有文件就不打开，避免无谓的同步开销
        if (!zipExistsOnDisk()) {
            return null;
        }
        synchronized (lock) {
            if (fs != null) {
                return fs;
            }
            // 双重检查
            if (!zipExistsOnDisk()) {
                return null;
            }
            fs = openZip(false);
            return fs;
        }
    }

    /**
     * 磁盘上是否存在"有效的" zip 文件（存在且非 0 字节）。
     * 0 字节文件不是合法 zip，视同不存在。
     */
    private boolean zipExistsOnDisk() {
        try {
            return Files.exists(zipFile) && Files.size(zipFile) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 真正创建/打开 ZipFS。
     *
     * @param createIfMissing 当磁盘上不存在时是否创建新文件
     */
    private FileSystem openZip(boolean createIfMissing) throws IOException {
        Path parent = zipFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        boolean fileExists = zipExistsOnDisk();
        Map<String, String> env = new HashMap<>();
        // 已存在则不创建（create=false 只是打开），不存在且允许创建时才创建
        env.put("create", String.valueOf(createIfMissing && !fileExists));
        URI uri = URI.create("jar:" + zipFile.toAbsolutePath().toUri());
        return FileSystems.newFileSystem(uri, env);
    }

    /** 是否已实际打开（发生过写入或读取过历史文件）。 */
    public boolean isOpened() {
        return fs != null;
    }

    /** 底层 zip 文件磁盘路径。 */
    public Path getZipFile() {
        return zipFile;
    }

    // ==================== 2. 文件写入（触发懒加载） ====================

    @Override
    public void write(String path, byte[] data) throws IOException {
        // 解码为文本用于索引
        String textContent;
        try {
            textContent = new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            textContent = "";
        }
        // addDocument 内部覆盖写盘 + 覆盖索引
        String np = normalizeVirtual(path);
        addDocument(extractFileName(np), data, extractParentDir(np), Collections.singletonList(textContent));
    }

    @Override
    public void write(String path, String content) throws IOException {
        write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void write(String path, String content, Charset cs) throws IOException {
        write(path, content.getBytes(cs));
    }

    // ==================== 3. 文件读取（不触发创建） ====================

    /**
     * 读取文件全部字节。
     * <p>文件不存在 / VFS 尚未创建时抛出 {@link NoSuchFileException}。
     */
    @Override
    public byte[] readAllBytes(String path) throws IOException {
        FileSystem local = getFsForRead();
        if (local == null) {
            throw new NoSuchFileException(path);
        }
        Path p = normalize(local, path);
        if (!Files.exists(p)) {
            throw new NoSuchFileException(path);
        }
        return Files.readAllBytes(p);
    }

    @Override
    public String readString(String path) throws IOException {
        return readString(path, StandardCharsets.UTF_8);
    }

    @Override
    public String readString(String path, Charset cs) throws IOException {
        return new String(readAllBytes(path), cs);
    }

    // ==================== 4. 目录与文件信息（不触发创建） ====================

    @Override
    public String listTree() throws IOException {
        StringBuilder sb = new StringBuilder();
        FileSystem local = getFsForRead();
        if (local == null) {
            return sb.toString();
        }
        Path root = local.getPath("/");
        if (!Files.exists(root)) {
            return sb.toString();
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                // ZipFS 中目录路径本身可能带尾斜杠（根目录为 "/"，子目录如 "/docs/"），
                // 直接追加 "/" 会产生 "//" 或 "/docs//"，先去掉尾部斜杠再统一追加。
                String dirStr = dir.toString();
                while (dirStr.endsWith("/")) {
                    dirStr = dirStr.substring(0, dirStr.length() - 1);
                }
                sb.append(dirStr).append("/\n");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                sb.append(file).append("\n");
                return FileVisitResult.CONTINUE;
            }
        });
        return sb.toString();
    }

    @Override
    public Stream<String> list(String dir) throws IOException {
        if (!isDirectory(normalizeVirtual(dir))) {
            throw new NotDirectoryException(dir);
        }
        FileSystem local = getFsForRead();
        if (local == null) {
            throw new NotDirectoryException(dir);
        }
        Path d = normalize(local, dir);
        return Files.list(d).map(p -> p.getFileName().toString());
    }

    @Override
    public boolean exists(String path) {
        try {
            FileSystem local = getFsForRead();
            if (local == null) {
                return false;
            }
            return Files.exists(normalize(local, path));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean isDirectory(String path) {
        try {
            FileSystem local = getFsForRead();
            if (local == null) {
                return false;
            }
            return Files.isDirectory(normalize(local, path));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean isRegularFile(String path) {
        try {
            FileSystem local = getFsForRead();
            if (local == null) {
                return false;
            }
            return Files.isRegularFile(normalize(local, path));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 文件大小。文件不存在时抛出 {@link NoSuchFileException}，
     * 调用方应先通过 {@link #exists(String)} 判断。
     */
    @Override
    public long size(String path) throws IOException {
        FileSystem local = getFsForRead();
        if (local == null) {
            throw new NoSuchFileException(path);
        }
        Path p = normalize(local, path);
        if (!Files.exists(p)) {
            throw new NoSuchFileException(path);
        }
        return Files.size(p);
    }

    // ==================== 5. 文件/目录操作 ====================

    @Override
    public void createDirectories(String dir) throws IOException {
        FileSystem fileSystem = ensureOpenForWrite();
        Files.createDirectories(normalize(fileSystem, dir));
    }

    @Override
    public void delete(String path) throws IOException {
        FileSystem local = getFsForRead();
        if (local == null) {
            throw new NoSuchFileException(path);
        }
        Path p = normalize(local, path);
        if (!Files.exists(p)) {
            throw new NoSuchFileException(path);
        }
        Files.delete(p);
    }

    @Override
    public boolean deleteIfExists(String path) throws IOException {
        FileSystem local = getFsForRead();
        if (local == null) {
            return false;
        }
        Path p = normalize(local, path);
        if (!Files.exists(p)) {
            return false;
        }
        Files.deleteIfExists(p);
        return true;
    }

    @Override
    public void deleteRecursively(String path) throws IOException {
        FileSystem local = getFsForRead();
        if (local == null) {
            return;
        }
        Path p = normalize(local, path);
        if (Files.isDirectory(p)) {
            // 递归删除目录
            try (Stream<Path> walk = Files.walk(p)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(child -> {
                    try {
                        Files.delete(child);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        } else if (Files.exists(p)) {
            Files.delete(p);
        }
    }

    @Override
    public void copy(String s, String t, CopyOption... ops) throws IOException {
        String ns = normalizeVirtual(s);
        String nt = normalizeVirtual(t);

        byte[] data = readAllBytes(ns);
        String text = new String(data, StandardCharsets.UTF_8);

        boolean replace = false;
        for (CopyOption opt : ops) {
            if (opt == StandardCopyOption.REPLACE_EXISTING) {
                replace = true;
            }
        }

        if (!replace && exists(nt)) {
            throw new FileAlreadyExistsException(nt);
        }

        // addDocument 内部覆盖写盘 + 覆盖索引
        addDocument(extractFileName(nt), data, extractParentDir(nt), Collections.singletonList(text));
    }

    @Override
    public void move(String s, String t, CopyOption... ops) throws IOException {
        String ns = normalizeVirtual(s);
        String nt = normalizeVirtual(t);

        // 如果源是目录，递归移动所有文件（ZipFS 的 Files.move 不支持目录递归移动）
        if (isDirectory(ns)) {
            String oldPrefix = ns.endsWith("/") ? ns : ns + "/";
            String newPrefix = nt.endsWith("/") ? nt : nt + "/";

            // 收集源目录下所有文件
            FileSystem fileSystem = ensureOpenForWrite();
            Path srcDir = normalize(fileSystem, s);
            List<String> oldPaths = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(srcDir)) {
                walk.filter(Files::isRegularFile).forEach(file ->
                        oldPaths.add(normalizeVirtual(file.toString())));
            }

            // 逐个复制文件到新位置（addDocument 内部写盘 + 索引）
            for (String oldPath : oldPaths) {
                byte[] data = readAllBytes(oldPath);
                String text = new String(data, StandardCharsets.UTF_8);
                String relativePath = oldPath.substring(oldPrefix.length());
                String newPath = normalizeVirtual(newPrefix + relativePath);
                addDocument(extractFileName(newPath), data, extractParentDir(newPath), Collections.singletonList(text));
            }

            // 删除旧目录及其内容
            deleteRecursively(ns);
            return;
        }

        // 源是文件，走复制+删除
        if (!exists(ns)) {
            throw new NoSuchFileException(s);
        }
        copy(s, t, ops);
        delete(s);
    }

    // ==================== 6. 全文检索 ====================

    /**
     * 添加文档（支持分段）。
     *
     * <p>将 sourceBytes（若为 null 则用分段拼接文本）写入 zip。docId 直接使用文件虚拟路径。
     *
     * @param fileName    原始文件名（如 report.pdf）
     * @param sourceBytes 源文件字节（可为 null）
     * @param filePath    目录路径（如 /docs）
     * @param textChunks  分段后的文本列表
     * @return 文档ID（即文件虚拟路径）
     */
    public String addDocument(String fileName, byte[] sourceBytes, String filePath, List<String> textChunks) {
        if (textChunks == null) {
            textChunks = Collections.singletonList("");
        }

        if (fileName == null || fileName.trim().isEmpty()) {
            fileName = "未命名-" + UUID.randomUUID().toString().replace("-", "") + ".txt";
        }

        // 组合完整虚拟路径
        String fullPath;
        if (filePath != null && filePath.endsWith("/")) {
            fullPath = filePath + fileName;
        } else {
            fullPath = (filePath == null ? "/" : filePath) + "/" + fileName;
        }
        fullPath = normalizeVirtual(fullPath);

        String fullText = String.join("", textChunks);
        if (fullText == null) {
            fullText = "";
        }

        if (sourceBytes == null) {
            sourceBytes = fullText.getBytes(StandardCharsets.UTF_8);
        }

        // 写入 zip（ZipFS 不支持覆盖已存在条目，需先删除）
        try {
            FileSystem fileSystem = ensureOpenForWrite();
            Path p = normalize(fileSystem, fullPath);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.deleteIfExists(p);
            Files.write(p, sourceBytes);
        } catch (IOException e) {
            throw new RuntimeException("写入文件失败: " + fullPath, e);
        }

        log.info("文档添加成功: docId={}, fileName={}, chunks={}", fullPath, fileName, textChunks.size());
        return fullPath;
    }

    /**
     * Grep 搜索（正则表达式）。
     *
     * @param regex      正则表达式
     * @param resultSize 最大返回数量
     * @return 匹配结果
     */
    @Override
    public List<SearchResult> grep(String regex, int resultSize) {
        List<SearchResult> results = new ArrayList<>();

        FileSystem local;
        try {
            local = getFsForRead();
        } catch (IOException e) {
            return results;
        }
        if (local == null) {
            return results;
        }

        Path root = local.getPath("/");
        if (!Files.exists(root)) {
            return results;
        }

        // 收集所有文件
        List<String> allFiles = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                    allFiles.add(file.toString());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("遍历文件失败: {}", e.getMessage());
            return results;
        }

        // 正则匹配
        java.util.regex.Pattern pattern;
        try {
            pattern = java.util.regex.Pattern.compile(regex);
        } catch (java.util.regex.PatternSyntaxException e) {
            log.warn("正则表达式语法错误: {}", e.getMessage());
            return results;
        }

        int count = 0;
        for (String filePath : allFiles) {
            if (count >= resultSize) {
                break;
            }
            try {
                byte[] data = readAllBytes(filePath);
                if (data == null || data.length == 0) {
                    continue;
                }
                String content = new String(data, StandardCharsets.UTF_8);
                if (pattern.matcher(content).find()) {
                    String fileName = extractFileName(filePath);
                    SearchResult result = new SearchResult(filePath, fileName, filePath, 1.0, "grep");
                    results.add(result);
                    count++;
                }
            } catch (IOException e) {
                log.debug("读取文件失败: {}", filePath);
            }
        }

        return results;
    }

    // ==================== 7. 释放 ====================

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (fs != null) {
                try {
                    fs.close();
                } finally {
                    fs = null;
                }
            }
        }
        log.debug("VFS 已关闭: sessionId={}", sessionId);
    }

    /**
     * 返回底层 FileSystem，可能为 null（尚未打开时）。
     */
    @Override
    public FileSystem getFileSystem() {
        return fs;
    }

    /**
     * 获取用于读取的 FileSystem。
     * <p>若磁盘上存在历史 zip 则打开并返回；尚未初始化（无 zip 文件）时返回 null。
     * 供需要直接使用 NIO API 的工具（如 Glob/Grep）调用，避免直接使用
     * {@link #getFileSystem()} 在懒加载未触发时拿到 null。
     *
     * @return 已打开的 FileSystem，或 null（尚未初始化）
     */
    public FileSystem getFileSystemForRead() throws IOException {
        return getFsForRead();
    }

    @Override
    public boolean isInitialized() {
        return zipExistsOnDisk();
    }

    // ==================== 内部工具 ====================

    /**
     * 统一路径前缀，避免 ZipFS 相对路径歧义。
     * 注意：必须传入对应的 FileSystem 实例，避免并发场景下读到 null。
     */
    private Path normalize(FileSystem fileSystem, String path) {
        if (path == null || path.isEmpty()) {
            return fileSystem.getPath("/");
        }
        return path.startsWith("/") ? fileSystem.getPath(path) : fileSystem.getPath("/" + path);
    }

    /**
     * 规范化虚拟路径
     */
    private static String normalizeVirtual(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        // 统一使用正斜杠
        path = path.replace("\\", "/");
        // 确保以 / 开头
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        // 合并多个连续斜杠
        path = path.replaceAll("/+", "/");
        // 移除尾部的 /
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * 提取文件名
     */
    private static String extractFileName(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /**
     * 提取父目录
     */
    private static String extractParentDir(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
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
