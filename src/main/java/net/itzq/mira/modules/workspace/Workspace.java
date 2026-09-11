package net.itzq.mira.modules.workspace;

import net.itzq.mira.modules.vfs.model.SearchResult;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.CopyOption;
import java.nio.file.FileSystem;
import java.util.List;
import java.util.stream.Stream;

/**
 * 工作空间统一接口
 *
 * <p>定义 Workspace（磁盘文件存储）和 VFS（ZipFS 虚拟文件系统）的共同 API。
 * 两者都提供会话级的虚拟文件系统能力，支持文件读写、目录操作和全文检索。
 *
 * <p>实现差异：
 * <ul>
 *     <li>{@link FileWorkspace} - 基于磁盘文件系统，源文件落盘，支持 Lucene 索引</li>
 *     <li>{@link net.itzq.mira.modules.vfs.VFS} - 基于 ZipFS，纯文本存储在 zip 文件中，支持 Lucene 索引</li>
 * </ul>
 *
 * @author tangzq
 */
public interface Workspace extends Closeable {

    // ==================== 1. 文件写入 ====================

    /**
     * 将字节数组写入指定路径的文件，若路径上已有文件则覆盖。
     * 写入时自动创建父目录。
     *
     * @param path 文件路径，如 "/data/hello.txt"
     * @param data 要写入的字节内容
     */
    void write(String path, byte[] data) throws IOException;

    /**
     * 将字符串以 UTF-8 编码写入文件。
     *
     * @param path    文件路径
     * @param content 字符串内容
     */
    void write(String path, String content) throws IOException;

    /**
     * 将字符串以指定编码写入文件。
     *
     * @param path    文件路径
     * @param content 字符串内容
     * @param charset 字符编码
     */
    void write(String path, String content, Charset charset) throws IOException;

    // ==================== 2. 文件读取 ====================

    /**
     * 读取文件全部字节。
     *
     * @param path 文件路径
     * @return 文件内容的字节数组，文件不存在时返回空数组
     */
    byte[] readAllBytes(String path) throws IOException;

    /**
     * 以 UTF-8 编码读取文件内容为字符串。
     *
     * @param path 文件路径
     * @return 文件内容字符串，文件不存在时返回 null
     */
    String readString(String path) throws IOException;

    /**
     * 以指定编码读取文件内容为字符串。
     *
     * @param path    文件路径
     * @param charset 字符编码
     * @return 文件内容字符串
     */
    String readString(String path, Charset charset) throws IOException;

    // ==================== 3. 目录与文件信息 ====================

    /**
     * 递归列出工作空间中所有文件和目录的树状结构。
     *
     * @return 以换行分隔的目录树字符串（目录以 '/' 结尾）
     */
    String listTree() throws IOException;

    /**
     * 列出指定目录下的直接子项（文件/目录）名称，不递归。
     *
     * @param dir 目录路径
     * @return 包含子项文件名的流
     */
    Stream<String> list(String dir) throws IOException;

    /**
     * 判断文件或目录是否存在。
     *
     * @param path 文件或目录路径
     * @return 是否存在
     */
    boolean exists(String path);

    /**
     * 判断路径是否为目录。
     *
     * @param path 路径
     * @return 是否为目录
     */
    boolean isDirectory(String path);

    /**
     * 判断路径是否为普通文件。
     *
     * @param path 路径
     * @return 是否为普通文件
     */
    boolean isRegularFile(String path);

    /**
     * 获取文件大小（字节数）。
     *
     * @param path 文件路径
     * @return 文件大小
     */
    long size(String path) throws IOException;

    // ==================== 4. 文件/目录操作 ====================

    /**
     * 创建目录（含所有不存在的父目录），类似 Files.createDirectories。
     *
     * @param dir 目录路径
     */
    void createDirectories(String dir) throws IOException;

    /**
     * 删除文件或空目录。
     *
     * @param path 文件或目录路径
     */
    void delete(String path) throws IOException;

    /**
     * 如果存在则删除文件或空目录。
     *
     * @param path 文件或目录路径
     * @return 是否确实删除了
     */
    boolean deleteIfExists(String path) throws IOException;

    /**
     * 递归删除目录及其下所有文件和子目录。
     * 如果路径指向文件，则删除该文件。
     *
     * @param path 目录或文件路径
     */
    void deleteRecursively(String path) throws IOException;

    /**
     * 复制文件到目标路径。
     *
     * @param source  源文件路径
     * @param target  目标文件路径
     * @param options 可选的复制选项
     */
    void copy(String source, String target, CopyOption... options) throws IOException;

    /**
     * 移动/重命名文件或目录。
     *
     * @param source  源路径
     * @param target  目标路径
     * @param options 可选的移动选项
     */
    void move(String source, String target, CopyOption... options) throws IOException;

    // ==================== 5. 全文检索 ====================

    /**
     * 搜索：返回相关文件列表。
     *
     * @param query      查询关键词
     * @param resultSize 最大返回数量
     * @return 相关文件列表
     */
    List<SearchResult> search(String query, int resultSize);

    /**
     * Grep 搜索（正则表达式）。
     *
     * @param regex      正则表达式
     * @param resultSize 最大返回数量
     * @return 匹配结果
     */
    List<SearchResult> grep(String regex, int resultSize);

    // ==================== 6. 生命周期 ====================

    /**
     * 关闭并释放资源（含 Lucene 索引）。
     */
    @Override
    void close() throws IOException;

    /**
     * 获取底层文件系统。
     *
     * @return FileSystem 实例
     */
    FileSystem getFileSystem();

    /**
     * 判断该工作空间是否已完成初始化（即磁盘存储目录已被实际创建）。
     *
     * @return 是否已初始化
     */
    boolean isInitialized();
}
