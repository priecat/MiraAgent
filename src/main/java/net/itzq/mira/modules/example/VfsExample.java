package net.itzq.mira.modules.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import net.itzq.mira.modules.runtime.KernelRuntime;
import net.itzq.mira.modules.vfs.VFS;
import net.itzq.mira.modules.vfs.model.SearchResult;
import net.itzq.mira.modules.workspace.WorkspaceConfig;

/**
 * 虚拟文件系统（VFS）示例 —— 多实例写法。
 *
 * <h3>与旧写法的区别</h3>
 * <ul>
 *   <li>旧：{@code VFS.init(config)} + {@code VFS.load(sessionId)}（进程级全局配置，
 *       多实例下同名会话会串）；</li>
 *   <li>新：{@code KernelRuntime.builder().vfsConfig(...)} 起运行时，再经
 *       {@code rt.vfs().loadVfs(sessionId)} 取会话实例——数据目录随运行时走，实例间天然隔离。</li>
 * </ul>
 *
 * <p>演示：懒加载（load 不建 zip、首次写入才建）、读写、分段文档、目录树、Grep 检索、文件操作。
 */
public class VfsExample {

    /** 32 位十六进制会话 id（协议要求，无连字符） */
    static final String SESSION_ID = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4";
    /** 另一个全新会话，用于演示"未写入前不产生文件" */
    static final String FRESH_ID = "f1e2d3c4b5a69788796a5b4c3d2e1f00";

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  VFS ZipFS 虚拟文件系统示例");
        System.out.println("========================================\n");

        WorkspaceConfig vfsConfig = new WorkspaceConfig();
        vfsConfig.setDataDir("./data/vfs-example");

        // 装配运行时：vfsConfig 决定 VFS 的落盘根目录（跟随运行时，多实例隔离）
        KernelRuntime rt = KernelRuntime.builder()
                .name("vfs-example")
                .dataDir("./data/vfs-example")
                .vfsConfig(vfsConfig)
                .build()
                .start();
        try {
            System.out.println("VFS 数据目录: " + rt.vfs().dataDir());

            demonstrateLazyInit(rt);

            try (VFS vfs = rt.vfs().loadVfs(SESSION_ID)) {
                basicReadWrite(vfs);
                documentsAndSearch(vfs);
                demonstrateFileOps(vfs);

                System.out.println("\n[最终目录树]\n" + vfs.listTree());
            }

            System.out.println("========================================");
            System.out.println("  所有示例运行成功！");
            System.out.println("========================================");
        } catch (Exception e) {
            System.err.println("示例运行失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            rt.close();
        }
    }

    /**
     * 演示懒加载：load 之后不创建 zip；读/查返回空且不产生空文件；首次写入才真正建 zip。
     */
    private static void demonstrateLazyInit(KernelRuntime rt) throws IOException {
        System.out.println("\n========== 延迟初始化演示 ==========");
        try (VFS vfs = rt.vfs().loadVfs(FRESH_ID)) {
            System.out.println("[load 之后，未写入]");
            System.out.println("  isOpened()       = " + vfs.isOpened());
            System.out.println("  getZipFile()     = " + vfs.getZipFile());
            System.out.println("  exists(/any)     = " + vfs.exists("/any"));
            System.out.println("  listTree() 为空  = " + vfs.listTree().isEmpty());

            vfs.write("/data/hello.txt", "Hello Lazy");

            System.out.println("[首次写入之后]");
            System.out.println("  isOpened()       = " + vfs.isOpened());
            System.out.println("  readString()     = " + vfs.readString("/data/hello.txt"));
            System.out.println("  listTree():\n" + vfs.listTree());
        }
        System.out.println("===================================\n");
    }

    /** 基本读写与类型判断；读缺失文件应抛 NoSuchFileException（而非静默返回空） */
    private static void basicReadWrite(VFS vfs) throws IOException {
        System.out.println("\n========== 基本读写 ==========");
        System.out.println("  sessionId    = " + SESSION_ID);
        System.out.println("  zipFile      = " + vfs.getZipFile());

        vfs.write("/data/hello.txt", "Hello VFS");
        System.out.println("  readString() = " + vfs.readString("/data/hello.txt"));
        System.out.println("  size()       = " + vfs.size("/data/hello.txt") + " bytes");
        System.out.println("  exists()     = " + vfs.exists("/data/hello.txt"));
        System.out.println("  isRegularFile= " + vfs.isRegularFile("/data/hello.txt"));
        System.out.println("  isDirectory()= " + vfs.isDirectory("/data/hello.txt"));

        try {
            vfs.readString("/data/not_exist.txt");
        } catch (NoSuchFileException e) {
            System.out.println("  [读取不存在] 正确抛出 NoSuchFileException: " + e.getFile());
        }
    }

    /** 添加分段文档 + Grep 检索 + 目录树 / 列目录 */
    private static void documentsAndSearch(VFS vfs) throws IOException {
        System.out.println("\n========== 文档与检索 ==========");

        byte[] pdfBytes = "%PDF-1.4 模拟PDF内容".getBytes(StandardCharsets.UTF_8);
        List<String> pdfChunks = Arrays.asList(
                "# 季度销售报告\n\n本季度销售额达到 1000 万元，同比增长 20%。",
                "## 产品分析\n\nA产品销售额 500 万，B产品销售额 300 万。",
                "## 总结\n\n整体业绩良好，建议继续加大市场投入。");
        String docId = vfs.addDocument("Q3销售报告.md", pdfBytes, "/docs/reports", pdfChunks);
        System.out.println("  [addDocument] docId = " + docId);

        String md = "# API 使用指南\n\n## 认证\n\n使用 Bearer Token 进行认证。";
        vfs.addDocument("API指南.md", null, "/docs/guides", Collections.singletonList(md));
        System.out.println("  [addDocument] API指南.md（sourceBytes=null，用文本填充）");

        System.out.println("\n  [列目录 /docs]");
        try (java.util.stream.Stream<String> list = vfs.list("/docs")) {
            list.collect(Collectors.toList()).forEach(name -> System.out.println("    " + name));
        }

        vfs.createDirectories("/empty/dir/nested");
        System.out.println("\n  [createDirectories] isDirectory(/empty/dir/nested) = "
                + vfs.isDirectory("/empty/dir/nested"));

        System.out.println("\n  [grep 销售额|认证]");
        List<SearchResult> hits = vfs.grep("销售额|认证", 10);
        for (SearchResult r : hits) {
            System.out.println("    - filePath=" + r.getFilePath()
                    + " fileName=" + r.getFileName()
                    + " (" + r.getSource() + ")");
        }
    }

    /** 文件操作：复制、移动、删除、递归删除 */
    private static void demonstrateFileOps(VFS vfs) throws IOException {
        System.out.println("\n========== 文件操作演示 ==========");

        vfs.write("/ops/fileA.txt", "Content A");
        vfs.write("/ops/sub/fileB.txt", "Content B");
        System.out.println("[准备] 写入 /ops/fileA.txt 和 /ops/sub/fileB.txt");

        vfs.copy("/ops/fileA.txt", "/ops/fileA_copy.txt");
        System.out.println("[copy] 源存在=" + vfs.exists("/ops/fileA.txt")
                + " 副本存在=" + vfs.exists("/ops/fileA_copy.txt")
                + " 副本内容=" + vfs.readString("/ops/fileA_copy.txt"));

        vfs.move("/ops/fileA.txt", "/ops/sub/fileA_moved.txt");
        System.out.println("[move] 原路径存在=" + vfs.exists("/ops/fileA.txt")
                + " 新路径存在=" + vfs.exists("/ops/sub/fileA_moved.txt"));

        vfs.move("/ops/sub", "/ops/renamed");
        System.out.println("[move 目录] /ops/sub 存在=" + vfs.exists("/ops/sub")
                + " /ops/renamed 是目录=" + vfs.isDirectory("/ops/renamed")
                + " 内容=" + vfs.readString("/ops/renamed/fileB.txt"));

        vfs.delete("/ops/fileA_copy.txt");
        System.out.println("[delete] 存在=" + vfs.exists("/ops/fileA_copy.txt"));

        vfs.deleteRecursively("/ops/renamed");
        System.out.println("[deleteRecursively] /ops/renamed 存在=" + vfs.exists("/ops/renamed"));

        try {
            vfs.delete("/ops/not_exist.txt");
        } catch (NoSuchFileException e) {
            System.out.println("[delete 不存在] 正确抛出 NoSuchFileException: " + e.getFile());
        }
        System.out.println("[deleteIfExists 不存在] 返回 = " + vfs.deleteIfExists("/ops/not_exist.txt"));
    }
}
