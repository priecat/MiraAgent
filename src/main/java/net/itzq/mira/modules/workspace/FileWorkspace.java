package net.itzq.mira.modules.workspace;

import net.itzq.mira.modules.vfs.model.Directory;
import net.itzq.mira.modules.vfs.model.Document;
import net.itzq.mira.modules.vfs.model.KBInfo;
import net.itzq.mira.modules.vfs.model.SearchResult;
import net.itzq.mira.modules.vfs.storage.LuceneStorage;
import net.itzq.mira.modules.workspace.storage.FileStorage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Workspace -- 磁盘文件存储工作空间
 *
 * <p>每个实例对应一个磁盘存储目录 + 一个 Lucene 索引目录：
 * <pre>
 *   &lt;dataDir&gt;/ab/cd/&lt;sessionId&gt;/storage/   实际文件落盘目录
 *   &lt;dataDir&gt;/ab/cd/&lt;sessionId&gt;/lucene/    全文索引目录
 * </pre>
 *
 * <pre>{@code
 * // 1. 全局初始化（应用启动时调用一次）
 * WorkspaceConfig config = new WorkspaceConfig();
 * config.setDataDir("./data/workspace-example");
 * Workspace.init(config);
 *
 * // 2. 创建/加载会话工作空间
 * try (Workspace vk = Workspace.load("abcdef1234567890abcdef1234567890")) {
 *
 *     vk.write("/data/hello.txt", "Hello");
 *     String content = vk.readString("/data/hello.txt");
 *     System.out.println(vk.listTree());
 *
 *     // 额外能力 -- 工作空间搜索
 *     List<SearchResult> results = vk.search("关键词", 5);
 * }
 * }</pre>
 *
 * @author tangzq
 */
public class FileWorkspace implements Workspace {

    private static final Logger log = LoggerFactory.getLogger(FileWorkspace.class);

    private static volatile WorkspaceConfig globalConfig;

    private final String sessionId;
    private final String storagePath;
    private final String lucenePath;
    private final FileStorage fileStorage;
    private final LuceneStorage luceneStorage;

    // ==================== 构造与生命周期 ====================

    private FileWorkspace(String sessionId) {
        this.sessionId = sessionId;

        // 计算路径: <dataDir>/ab/cd/<sessionId>/（不立即创建磁盘目录，延迟到首次使用时）
        String dir = buildSessionDir(sessionId);
        this.storagePath = dir + File.separator + "storage";
        this.lucenePath = dir + File.separator + "lucene";

        // 初始化存储层（均为延迟初始化，构造时不触碰磁盘）
        this.fileStorage = new FileStorage(storagePath, globalConfig.getMapDir());
        this.luceneStorage = globalConfig.isLuceneEnabled() ? new LuceneStorage(lucenePath) : null;


    }

    /**
     * 构建会话目录路径: <dataDir>/ab/cd/<sessionId>/
     */
    private static String buildSessionDir(String sessionId) {
        String a = sessionId.substring(0, 2);
        String b = sessionId.substring(2, 4);
        return globalConfig.getDataDir() + File.separator + a + File.separator + b + File.separator + sessionId;
    }

    /**
     * 全局初始化（必须在使用前调用一次）
     */
    public static void init(WorkspaceConfig config) {
        if (globalConfig != null) {
            log.warn("Workspace 已初始化，忽略重复调用");
            return;
        }
        if (config == null) {
            throw new IllegalArgumentException("config 不能为 null");
        }
        if (config.getDataDir() == null || config.getDataDir().isEmpty()) {
            throw new IllegalArgumentException("dataDir 不能为 null");
        }
        globalConfig = config;
        log.info("Workspace 初始化完成，数据目录: {}", config.getDataDir());
    }

    /**
     * 获取全局配置
     */
    public static WorkspaceConfig getConfig() {
        if (globalConfig == null) {
            throw new IllegalStateException("Workspace 未初始化，请先调用 Workspace.init(config)");
        }
        return globalConfig;
    }

    /**
     * 加载或创建会话工作空间
     *
     * @param sessionId 32位UUID（无连字符）
     * @return Workspace 实例（实现了 Closeable，推荐 try-with-resources）
     */
    public static FileWorkspace load(String sessionId) {
        if (globalConfig == null) {
            throw new IllegalStateException("Workspace 未初始化，请先调用 Workspace.init(config)");
        }
        validateSessionId(sessionId);
        try {
            return new FileWorkspace(sessionId);
        } catch (Exception e) {
            throw new RuntimeException("加载工作空间失败: " + sessionId, e);
        }
    }

    /**
     * 关闭并释放资源（含 Lucene 索引）
     */
    @Override
    public void close() throws IOException {
        if (luceneStorage != null) {
            luceneStorage.close();
        }
        log.debug("FileWorkspace 已关闭: sessionId={}", sessionId);
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

        // 解码为文本用于工作空间索引
        String textContent;
        try {
            textContent = new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            textContent = "";
        }

        // addDocument 内部覆盖写盘 + 覆盖索引
        addDocument(fileName, data, parentDir, Collections.singletonList(textContent));
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
        byte[] data = getSourceFileByPath(np);
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
        String content = getKnowledgeTextByPath(np);
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
     * 递归列出工作空间中所有文件和目录的树状结构。
     *
     * @return 以换行分隔的目录树字符串（目录以 '/' 结尾）
     */
    public String listTree() throws IOException {
        TreeSet<String> allPaths = new TreeSet<>();
        allPaths.add("/");

        // 收集所有目录
        for (Directory dir : getStorage().listSubDirectories("/")) {
            collectAllPaths(dir, allPaths);
        }

        // 收集所有文件
        List<Document> docs = listDocuments();
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
        List<String> entries = getStorage().listDirectory(np);
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
        return getStorage().isRegularFile(normalizePath(path));
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
        getStorage().createDirectory(np);
    }

    /**
     * 删除文件或空目录。
     *
     * @param path 文件或目录路径
     * @throws NoSuchFileException 路径不存在时抛出
     */
    public void delete(String path) throws IOException {
        String np = normalizePath(path);

        // 先尝试作为文件删除
        Document doc = getStorage().getDocumentByPath(np);
        if (doc != null) {
            deleteDocument(doc.getDocId());
            return;
        }

        // 再尝试作为目录删除（非递归，目录必须为空）
        if (getStorage().isDirectory(np)) {
            getStorage().deleteDirectory(np, false);
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
            deleteDocument(doc.getDocId());
            return true;
        }

        // 目录
        if (getStorage().isDirectory(np)) {
            getStorage().deleteDirectory(np, false);
            return true;
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
            deleteDocument(doc.getDocId());
            return;
        }

        // 目录：递归删除
        if (getStorage().isDirectory(np)) {
            getStorage().deleteDirectory(np, true);
            deleteIndexByPrefix(np);
        }
    }

    /**
     * 复制文件到目标路径。
     *
     * @param source  源文件路径
     * @param target  目标文件路径
     * @param options 可选的复制选项，支持 {@link StandardCopyOption#REPLACE_EXISTING}
     * @throws NoSuchFileException                      源文件不存在时抛出
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
        if (existingTarget != null && !replace) {
            throw new java.nio.file.FileAlreadyExistsException(nt);
        }

        addDocument(extractFileName(nt), data, extractParentDir(nt), Collections.singletonList(text));
    }

    /**
     * 移动/重命名文件或目录。
     *
     * @param source  源路径
     * @param target  目标路径
     * @param options 可选的移动选项，支持 {@link StandardCopyOption#REPLACE_EXISTING}
     */
    public void move(String source, String target, CopyOption... options) throws IOException {
        String ns = normalizePath(source);
        // 如果源是目录，走目录重命名逻辑
        if (getStorage().isDirectory(ns) && getStorage().getDocumentByPath(ns) == null) {
            String nt = normalizePath(target);
            getStorage().renameDirectory(ns, nt);
            reindexAfterDirMove(ns, nt);
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
        String no = normalizePath(oldPath);
        String nn = normalizePath(newPath);
        getStorage().renameDirectory(no, nn);
        reindexAfterDirMove(no, nn);
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
        getStorage().deleteDirectory(np, recursive);
        if (recursive) {
            deleteIndexByPrefix(np);
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

    // ==================== 5. 扩展能力（工作空间特有） ====================

    /**
     * 添加文档（支持分段）。
     *
     * <p>将 sourceBytes（若为 null 则用分段拼接文本）写入磁盘，
     * 并把分段拼接后的完整文本索引进 Lucene。docId 直接使用文件虚拟路径。
     *
     * @param fileName      原始文件名（如 report.pdf）
     * @param sourceBytes   源文件字节（可为 null）
     * @param filePath      目录路径（如 /docs）
     * @param textChunks    分段后的文本列表
     * @return 文档ID（即文件虚拟路径）
     */
    public String addDocument(String fileName, byte[] sourceBytes, String filePath, List<String> textChunks) {
        if (textChunks == null) {
            textChunks = Collections.singletonList("");
        }

        if (StringUtils.isBlank(fileName)) {
            fileName = "未命名-" + UUID.randomUUID().toString().replace("-", "") + ".txt";
        }

        // 组合完整虚拟路径
        String fullPath;
        if (StringUtils.endsWith(filePath, "/")) {
            fullPath = filePath + fileName;
        } else {
            fullPath = (filePath == null ? "/" : filePath) + "/" + fileName;
        }
        fullPath = FileStorage.normalizeVirtual(fullPath);

        String fullText = String.join("", textChunks);
        if (fullText == null) {
            fullText = "";
        }

        if (sourceBytes == null) {
            sourceBytes = fullText.getBytes(StandardCharsets.UTF_8);
        }

        try {
            // 1. 写入磁盘
            fileStorage.writeFile(fullPath, sourceBytes);
        } catch (IOException e) {
            throw new RuntimeException("写入文件失败: " + fullPath, e);
        }

        // 2. Lucene 索引（索引完整文本）
        if (luceneStorage != null) {
            luceneStorage.indexDocument(fullPath, fullPath, fullText);
        }

        log.info("文档添加成功: docId={}, fileName={}, chunks={}", fullPath, fileName, textChunks.size());
        return fullPath;
    }

    /**
     * 搜索：返回相关文件列表（文件级别，非片段）。
     *
     * @param query      查询关键词
     * @param resultSize 最大返回数量
     * @return 相关文件列表
     */
    public List<SearchResult> search(String query, int resultSize) {

        // 未启用 Lucene 索引时，打印警告并返回空结果
        if (!globalConfig.isLuceneEnabled() || luceneStorage == null) {
            log.warn("Lucene 索引未启用，搜索返回空结果: query={}", query);
            return new ArrayList<>();
        }

        List<ChunkMatch> chunkMatches = new ArrayList<>();

        // Lucene 全文检索（LuceneStorage 内部按需懒加载：仅打开已存在的索引，不创建新目录）
        List<SearchResult> luceneResults = luceneStorage.search(query, globalConfig.getLuceneTopN());
        for (SearchResult r : luceneResults) {
            chunkMatches.add(new ChunkMatch(r.getDocId(), 0, r.getScore(), "lucene"));
        }
        log.debug("Lucene 搜索返回 {} 个结果", luceneResults.size());

        // 聚合到文件级别
        Map<String, SearchResult> fileResults = new LinkedHashMap<>();
        for (ChunkMatch match : chunkMatches) {
            String docId = match.docId;
            if (docId == null) {
                continue;
            }

            fileResults.computeIfAbsent(docId, id -> {
                Document doc = fileStorage.getDocumentByPath(id);
                if (doc == null) {
                    return null;
                }
                SearchResult sr = new SearchResult(doc.getDocId(), doc.getFileName(), doc.getFilePath(), 0.0, match.source);
                sr.setRealPath(doc.getRealPath());
                sr.setMappedPath(doc.getMappedPath());
                return sr;
            });

            SearchResult result = fileResults.get(docId);
            if (result != null) {
                if (match.score > result.getScore()) {
                    result.setScore(match.score);
                }
                result.addMatchedChunk(new SearchResult.ChunkMatch(null,
                        match.chunkIndex,
                        match.score,
                        "分段 " + match.chunkIndex));
            }
        }

        // 按最高相似度排序
        List<SearchResult> results = fileResults.values()
                .stream()
                .filter(Objects::nonNull)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());

        // 限制数量
        if (results.size() > resultSize) {
            results = results.subList(0, resultSize);
        }

        log.info("搜索完成: query={}, 结果数={}", query, results.size());
        return results;
    }

    /**
     * Grep 搜索（正则表达式）。
     *
     * @param regex      正则表达式
     * @param resultSize 最大返回数量
     * @return 匹配结果
     */
    public List<SearchResult> grep(String regex, int resultSize) {
        List<SearchResult> results = new ArrayList<>();

        List<FileStorage.GrepResult> grepResults = fileStorage.grepFiles(regex, resultSize);

        for (FileStorage.GrepResult grepResult : grepResults) {
            SearchResult result = new SearchResult(grepResult.getDocId(),
                    grepResult.getFileName(),
                    grepResult.getFilePath(),
                    1.0,
                    "grep");
            result.setRealPath(grepResult.getRealPath());
            result.setMappedPath(grepResult.getMappedPath());
            results.add(result);
        }

        return results;
    }

    /**
     * 获取源文件（通过路径）。
     */
    public byte[] getSourceFileByPath(String filePath) {
        try {
            return fileStorage.readFile(filePath);
        } catch (IOException e) {
            log.warn("读取源文件失败: {}", filePath);
            return null;
        }
    }

    /**
     * 获取知识文本（通过路径），以 UTF-8 解码。
     */
    public String getKnowledgeTextByPath(String filePath) {
        byte[] data = getSourceFileByPath(filePath);
        if (data == null) {
            return null;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * 删除文档（docId 即文件虚拟路径），同时删除磁盘文件与 Lucene 索引。
     */
    public void deleteDocument(String docId) {
        try {
            fileStorage.deleteFile(docId);
        } catch (IOException e) {
            log.warn("删除文件失败: {}", docId);
        }
        if (luceneStorage != null) {
            luceneStorage.deleteDocument(docId);
        }
        log.info("文档已删除: docId={}", docId);
    }

    /**
     * 列出所有文档。
     */
    public List<Document> listDocuments() {
        return fileStorage.listDocuments();
    }

    // ==================== 索引同步（供目录操作调用）====================

    /**
     * 目录移动/重命名后同步 Lucene 索引：删除旧前缀下的索引，按新路径重新索引。
     *
     * @param oldDirPath 旧目录虚拟路径（不带尾部 /）
     * @param newDirPath 新目录虚拟路径（不带尾部 /）
     */
    public void reindexAfterDirMove(String oldDirPath, String newDirPath) {
        if (luceneStorage == null) {
            return;
        }
        String oldPrefix = oldDirPath.endsWith("/") ? oldDirPath : oldDirPath + "/";
        // 删除旧前缀的所有索引
        luceneStorage.deleteByPrefix(oldPrefix);
        // 对新目录下所有文件重新建立索引
        for (String path : fileStorage.listFilePathsUnder(newDirPath)) {
            String text = getKnowledgeTextByPath(path);
            luceneStorage.indexDocument(path, path, text == null ? "" : text);
        }
    }

    /**
     * 删除目录后同步删除该目录前缀下的所有 Lucene 索引。
     */
    public void deleteIndexByPrefix(String dirPath) {
        if (luceneStorage == null) {
            return;
        }
        String prefix = dirPath.endsWith("/") ? dirPath : dirPath + "/";
        luceneStorage.deleteByPrefix(prefix);
    }

    /**
     * 获取文件系统。
     *
     * <p>注意：文件存储版直接使用系统默认文件系统（真实磁盘），
     * 请配合 {@link FileStorage#toReal(String)} 将虚拟路径转换为真实路径后使用。
     */
    public FileSystem getFileSystem() {
        return FileSystems.getDefault();
    }

    /**
     * 获取工作空间统计信息。
     */
    public KBInfo getInfo() {
        return fileStorage.getInfo(sessionId);
    }

    /**
     * 判断该会话工作空间是否已完成初始化（即磁盘存储目录已被实际创建）。
     * <p>仅在首次真正写入/创建目录（上传）时才进行初始化；未初始化时返回 false。
     * 此时路径仍可获取（但实际不存在于磁盘），统计视为无文件，搜索返回空。</p>
     */
    public boolean isInitialized() {
        return fileStorage.isInitialized();
    }

    // ==================== 文件存储扩展（真实磁盘路径） ====================

    /**
     * 将虚拟路径转换为磁盘真实路径（文件存储版特有的便捷方法）。
     *
     * @param virtualPath 虚拟路径，如 /docs/report.md
     * @return 磁盘真实路径
     */
    public Path toRealPath(String virtualPath) {
        return getStorage().toReal(normalizePath(virtualPath));
    }

    /**
     * 获取存储根目录（对应虚拟路径 "/"）的磁盘真实路径。
     */
    public Path getStorageRoot() {
        return getStorage().getStorageRoot();
    }

    // ==================== 三路径概念（虚拟 / 真实 / 映射）====================

    /**
     * 1. 虚拟根（Storage 虚拟路径的根）：恒为 "/"。
     */
    public String getVirtualRoot() {
        return "/";
    }

    /**
     * 2. 真实根：磁盘上 storage 目录的真实路径（等价于 {@link #getStorageRoot()}）。
     */
    public Path getRealStorageRoot() {
        return getStorage().getStorageRoot();
    }

    /**
     * 3. 映射根：mapDir 配置（如 /workspace）。未配置时返回存储根目录的真实路径（映射路径==真实路径）。
     */
    public String getMappedRoot() {
        return getStorage().toMapped("/");
    }

    /**
     * 将虚拟路径转换为映射路径（mapDir + 虚拟路径）。
     *
     * <pre>
     *   mapDir="/workspace", 虚拟路径="/data/a.txt"  =>  "/workspace/data/a.txt"
     *   mapDir 未配置时，映射路径等于真实路径（toReal 结果）。
     * </pre>
     *
     * @param virtualPath 虚拟路径，如 /docs/report.md
     * @return 映射路径
     */
    public String toMappedPath(String virtualPath) {
        return getStorage().toMapped(normalizePath(virtualPath));
    }

    // ==================== 内部工具 ====================

    private FileStorage getStorage() {
        return fileStorage;
    }

    private static String normalizePath(String path) {
        return FileStorage.normalizeVirtual(path);
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

    // ==================== 内部类 ====================

    private static class ChunkMatch {
        final String docId;
        final int chunkIndex;
        final double score;
        final String source;

        ChunkMatch(String docId, int chunkIndex, double score, String source) {
            this.docId = docId;
            this.chunkIndex = chunkIndex;
            this.score = score;
            this.source = source;
        }
    }
}
