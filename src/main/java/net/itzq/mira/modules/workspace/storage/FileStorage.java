package net.itzq.mira.modules.workspace.storage;

import net.itzq.mira.modules.vfs.model.Directory;
import net.itzq.mira.modules.vfs.model.Document;
import net.itzq.mira.modules.vfs.model.KBInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 磁盘文件存储层
 *
 * <p>将工作空间的"虚拟路径"（以 / 开头、/ 分隔）映射到磁盘上的真实文件：
 * <pre>
 *   虚拟路径 /docs/report.md
 *   -> 真实路径 &lt;storageRoot&gt;/docs/report.md
 * </pre>
 * 其中 storageRoot 为 {@code <dataDir>/ab/cd/<sessionId>/storage}。
 *
 * <p>不再使用 SQLite，元数据（大小、时间）直接从磁盘文件属性读取；
 * docId 直接使用文件的虚拟路径，天然唯一。
 *
 * @author tangzq
 */
public class FileStorage {

    private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

    /** 存储根目录（对应虚拟路径 "/"） */
    private final Path storageRoot;

    /** 映射根目录（mapDir，虚拟路径风格，如 /workspace）；为 null 时映射路径==真实路径 */
    private final String mapDir;

    /** 是否已实际在磁盘上创建存储目录（延迟初始化标记） */
    private volatile boolean initialized = false;

    public FileStorage(String storageDir, String mapDir) {
        this.storageRoot = Paths.get(storageDir).toAbsolutePath().normalize();
        // mapDir 为 null/空时保持 null，表示映射路径==真实路径
        this.mapDir = (mapDir == null || mapDir.isEmpty()) ?
                null :
                Paths.get(mapDir).toAbsolutePath().normalize().toString();
        // 延迟初始化：构造时不再创建磁盘目录，等待首次真正写入/创建目录时按需创建
        log.debug("FileStorage 已创建（延迟初始化）: storageRoot={}, mapDir={}", storageRoot, this.mapDir);
    }

    public Path getStorageRoot() {
        return storageRoot;
    }

    /**
     * 判断存储目录是否已在磁盘上实际创建（即已完成初始化）。
     * 既检查内存标记，也直接探测磁盘，保证跨 JVM 重启后结果正确。
     */
    public boolean isInitialized() {
        return initialized || Files.isDirectory(storageRoot);
    }

    /**
     * 确保存储目录已在磁盘上创建（幂等、线程安全）。
     * 在首次写入文件、创建目录等"真正使用"时调用。
     */
    public synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        if (Files.isDirectory(storageRoot)) {
            initialized = true;
            return;
        }
        try {
            Files.createDirectories(storageRoot);
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
            Files.setPosixFilePermissions(storageRoot, perms); // 所有用户可读可写可执行

            initialized = true;
            log.info("FileStorage 已初始化（磁盘目录已创建）: storageRoot={}", storageRoot);
        } catch (IOException e) {
            throw new RuntimeException("创建存储根目录失败: " + storageRoot, e);
        }
    }

    /**
     * 虚拟路径 -> 映射路径（mapDir + 虚拟路径）。
     * <pre>
     *   mapDir="/workspace", 虚拟路径="/data/a.txt"  =>  "/workspace/data/a.txt"
     *   mapDir 未配置时，映射路径等于真实路径（toReal 结果）。
     * </pre>
     */
    public String toMapped(String virtualPath) {
        String vp = normalizeVirtual(virtualPath);
        if (mapDir == null) {
            // 未配置 mapDir 时，映射路径等于真实路径
            return toReal(virtualPath).toString();
        }
        String mapped = mapDir + vp;          // 两者均带前导 /
        mapped = mapped.replaceAll("/+", "/"); // 合并重复斜杠
        return mapped;
    }

    // ==================== 路径映射 ====================

    /**
     * 虚拟路径 -> 磁盘真实路径。
     */
    public Path toReal(String virtualPath) {
        String rel = normalizeVirtual(virtualPath);
        // 去掉首个 "/"
        rel = rel.startsWith("/") ? rel.substring(1) : rel;
        if (rel.isEmpty()) {
            return storageRoot;
        }
        Path resolved = storageRoot.resolve(rel).normalize();
        // 安全校验：禁止越过存储根目录（防止 ../ 逃逸）
        if (!resolved.startsWith(storageRoot)) {
            throw new IllegalArgumentException("非法路径（越界）: " + virtualPath);
        }
        return resolved;
    }

    /**
     * 磁盘真实路径 -> 虚拟路径（以 / 开头，/ 分隔，不带尾部 /）。
     */
    public String toVirtual(Path realPath) {
        Path abs = realPath.toAbsolutePath().normalize();
        if (abs.equals(storageRoot)) {
            return "/";
        }
        Path rel = storageRoot.relativize(abs);
        return "/" + rel.toString().replace(File.separatorChar, '/');
    }

    /**
     * 归一化虚拟路径：反斜杠转正斜杠，确保以 / 开头，去掉尾部 /（根除外）。
     */
    public static String normalizeVirtual(String path) {
        if (path == null) {
            return "/";
        }
        String normalized = path.replace("\\", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        // 合并多个连续斜杠
        normalized = normalized.replaceAll("/+", "/");
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    // ==================== 文件读写 ====================

    /**
     * 写入文件（覆盖），自动创建父目录。
     */
    public void writeFile(String virtualPath, byte[] data) throws IOException {
        ensureInitialized();
        Path real = toReal(virtualPath);
        Path parent = real.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(real, data, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /**
     * 读取文件全部字节；文件不存在返回 null。
     */
    public byte[] readFile(String virtualPath) throws IOException {
        Path real = toReal(virtualPath);
        if (!Files.isRegularFile(real)) {
            return null;
        }
        return Files.readAllBytes(real);
    }

    /**
     * 删除文件；返回是否确实删除。
     */
    public boolean deleteFile(String virtualPath) throws IOException {
        Path real = toReal(virtualPath);
        if (Files.isRegularFile(real)) {
            Files.delete(real);
            return true;
        }
        return false;
    }

    // ==================== 目录与文件信息 ====================

    /**
     * 创建目录（含父目录）。
     */
    public void createDirectory(String dirPath) throws IOException {
        ensureInitialized();
        Path real = toReal(dirPath);
        Files.createDirectories(real);
    }

    /**
     * 路径是否存在（文件或目录）。
     */
    public boolean existsPath(String path) {
        return Files.exists(toReal(path));
    }

    /**
     * 是否为目录。
     */
    public boolean isDirectory(String path) {
        return Files.isDirectory(toReal(path));
    }

    /**
     * 是否为普通文件。
     */
    public boolean isRegularFile(String path) {
        return Files.isRegularFile(toReal(path));
    }

    /**
     * 获取文件对应的 Document（不存在返回 null）。
     */
    public Document getDocumentByPath(String path) {
        Path real = toReal(path);
        if (!Files.isRegularFile(real)) {
            return null;
        }
        return buildDocument(real);
    }

    /**
     * 列出目录下的直接子项（文件名 + 目录名，目录以 "/" 结尾），不递归。
     */
    public List<String> listDirectory(String dirPath) throws IOException {
        Path real = toReal(dirPath);
        if (!Files.isDirectory(real)) {
            throw new NotDirectoryException(dirPath);
        }
        List<String> entries = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(real)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    entries.add(name + "/");
                } else {
                    entries.add(name);
                }
            }
        }
        return entries;
    }

    /**
     * 列出目录下的直接子目录（Directory 对象），不含文件。
     */
    public List<Directory> listSubDirectories(String dirPath) {
        Path real = toReal(dirPath);
        List<Directory> dirs = new ArrayList<>();
        if (!Files.isDirectory(real)) {
            return dirs;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(real)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) {
                    dirs.add(buildDirectory(p));
                }
            }
        } catch (IOException e) {
            log.warn("列出子目录失败: {}", e.getMessage());
        }
        return dirs;
    }

    /**
     * 获取目录信息（不存在返回 null）。
     */
    public Directory getDirectory(String dirPath) {
        Path real = toReal(dirPath);
        if (!Files.isDirectory(real)) {
            return null;
        }
        return buildDirectory(real);
    }

    /**
     * 删除目录。
     *
     * @param recursive true 递归删除子目录和文件；false 仅删除空目录
     */
    public void deleteDirectory(String dirPath, boolean recursive) throws IOException {
        Path real = toReal(dirPath);
        if (!Files.isDirectory(real)) {
            return;
        }
        if (!recursive) {
            // 空目录才可删除
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(real)) {
                if (ds.iterator().hasNext()) {
                    throw new DirectoryNotEmptyException(dirPath);
                }
            }
            Files.delete(real);
            return;
        }
        // 递归删除
        deleteRecursively(real);
    }

    /**
     * 重命名/移动目录，级联更新（磁盘上目录移动天然级联）。
     */
    public void renameDirectory(String oldPath, String newPath) throws IOException {
        Path oldReal = toReal(oldPath);
        Path newReal = toReal(newPath);
        if (!Files.isDirectory(oldReal)) {
            throw new NoSuchFileException(oldPath);
        }
        Path parent = newReal.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Files.move(oldReal, newReal, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | InvalidPathException e) {
            Files.move(oldReal, newReal);
        }
    }

    // ==================== 文档遍历 / 检索支撑 ====================

    /**
     * 递归列出存储区内所有文件（转为 Document）。
     */
    public List<Document> listDocuments() {
        List<Document> docs = new ArrayList<>();
        if (!Files.isDirectory(storageRoot)) {
            return docs;
        }
        try (Stream<Path> stream = Files.walk(storageRoot)) {
            stream.filter(Files::isRegularFile)
                    .forEach(p -> docs.add(buildDocument(p)));
        } catch (IOException e) {
            log.warn("遍历文档失败: {}", e.getMessage());
        }
        return docs;
    }

    /**
     * 递归列出存储区内所有文件的虚拟路径（用于目录移动时同步索引）。
     */
    public List<String> listFilePathsUnder(String dirPath) {
        Path real = toReal(dirPath);
        List<String> paths = new ArrayList<>();
        if (!Files.exists(real)) {
            return paths;
        }
        try (Stream<Path> stream = Files.walk(real)) {
            stream.filter(Files::isRegularFile)
                    .forEach(p -> paths.add(toVirtual(p)));
        } catch (IOException e) {
            log.warn("遍历文件路径失败: {}", e.getMessage());
        }
        return paths;
    }

    /**
     * Grep：对所有文件的文本内容做正则匹配，返回命中的文件。
     */
    public List<GrepResult> grepFiles(String regex, int limit) {
        List<GrepResult> results = new ArrayList<>();
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (Exception e) {
            log.warn("正则表达式非法: {}", regex);
            return results;
        }

        List<Document> docs = listDocuments();
        for (Document doc : docs) {
            if (results.size() >= limit) {
                break;
            }
            try {
                byte[] data = Files.readAllBytes(toReal(doc.getFilePath()));
                String text = new String(data, StandardCharsets.UTF_8);
                if (pattern.matcher(text).find()) {
                    results.add(new GrepResult(doc.getDocId(), doc.getFileName(),
                            doc.getFilePath(), doc.getRealPath(), doc.getMappedPath()));
                }
            } catch (IOException e) {
                log.debug("读取文件失败，跳过: {}", doc.getFilePath());
            }
        }
        return results;
    }

    // ==================== 统计信息 ====================

    public KBInfo getInfo(String sessionId) {
        KBInfo info = new KBInfo(sessionId);
        List<Document> docs = listDocuments();
        long sourceTotal = 0;
        long textTotal = 0;
        for (Document doc : docs) {
            sourceTotal += doc.getSourceSize();
            textTotal += doc.getTextSize();
        }
        info.setTotalDocuments(docs.size());
        info.setTotalChunks(docs.size());
        info.setSourceTotalSize(sourceTotal);
        info.setTextTotalSize(textTotal);
        info.setTotalDirectories(countDirectories());
        info.setInitialized(isInitialized());
        return info;
    }

    /**
     * 统计存储目录下（不含根本身）的子目录数量。
     * 未初始化（磁盘目录不存在）时返回 0。
     */
    private int countDirectories() {
        if (!Files.isDirectory(storageRoot)) {
            return 0;
        }
        int[] count = {0};
        try (Stream<Path> stream = Files.walk(storageRoot)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> !p.equals(storageRoot))
                    .forEach(p -> count[0]++);
        } catch (IOException e) {
            log.warn("统计目录数失败: {}", e.getMessage());
        }
        return count[0];
    }

    // ==================== 内部工具 ====================

    private Document buildDocument(Path real) {
        String virtualPath = toVirtual(real);
        String fileName = real.getFileName().toString();
        Document doc = new Document(virtualPath, fileName, virtualPath);
        doc.setRealPath(real.toString());
        doc.setMappedPath(toMapped(virtualPath));
        doc.setFileExt(getFileExtension(fileName));
        doc.setChunkCount(1);
        try {
            BasicFileAttributes attrs = Files.readAttributes(real, BasicFileAttributes.class);
            long size = attrs.size();
            doc.setSourceSize(size);
            doc.setTextSize(size);
            doc.setCreatedAt(attrs.creationTime().toMillis());
            doc.setUpdatedAt(attrs.lastModifiedTime().toMillis());
        } catch (IOException e) {
            log.debug("读取文件属性失败: {}", virtualPath);
        }
        return doc;
    }

    private Directory buildDirectory(Path real) {
        String virtualPath = toVirtual(real);
        String dirPath = virtualPath.endsWith("/") ? virtualPath : virtualPath + "/";
        String dirName = real.getFileName() != null ? real.getFileName().toString() : "";
        Path parent = real.getParent();
        String parentPath = parent != null ? toVirtual(parent) : "/";
        if (!parentPath.endsWith("/")) {
            parentPath = parentPath + "/";
        }
        Directory dir = new Directory(dirPath, dirPath, dirName, parentPath);
        dir.setRealPath(real.toString());
        dir.setMappedPath(toMapped(virtualPath));
        try {
            BasicFileAttributes attrs = Files.readAttributes(real, BasicFileAttributes.class);
            dir.setCreatedAt(attrs.creationTime().toMillis());
            dir.setUpdatedAt(attrs.lastModifiedTime().toMillis());
        } catch (IOException ignored) {
        }
        return dir;
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            // 逆序删除：先文件后目录
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1) : "";
    }

    // ==================== Grep 结果 ====================

    /**
     * Grep 命中结果。
     */
    public static class GrepResult {
        private final String docId;
        private final String fileName;
        private final String filePath;
        private final String realPath;
        private final String mappedPath;

        public GrepResult(String docId, String fileName, String filePath, String realPath, String mappedPath) {
            this.docId = docId;
            this.fileName = fileName;
            this.filePath = filePath;
            this.realPath = realPath;
            this.mappedPath = mappedPath;
        }

        public String getDocId() {
            return docId;
        }

        public String getFileName() {
            return fileName;
        }

        public String getFilePath() {
            return filePath;
        }

        public String getRealPath() {
            return realPath;
        }

        public String getMappedPath() {
            return mappedPath;
        }
    }
}
