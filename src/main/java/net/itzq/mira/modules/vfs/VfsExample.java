package net.itzq.mira.modules.vfs;

import net.itzq.mira.modules.vfs.model.SearchResult;
import net.itzq.mira.modules.workspace.WorkspaceConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * VFS 示例类 - 演示基于 ZipFS 的虚拟文件系统完整功能
 *
 * <p>演示内容：
 * <ol>
 *     <li>延迟初始化（load 后不创建 zip，首次写入才创建）</li>
 *     <li>文件读写</li>
 *     <li>添加分段文档 + Lucene 索引</li>
 *     <li>目录树、列目录</li>
 *     <li>全文搜索（Lucene）与 Grep（正则）</li>
 *     <li>文件操作：复制、移动、删除、递归删除</li>
 *     <li>文件信息：大小、存在性、类型判断</li>
 * </ol>
 *
 * @author tangzq
 */
public class VfsExample {

    static String sessionId = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4";

    /**
     * 演示延迟初始化：
     * 1. VFS.load 后未创建 zip 文件
     * 2. 读取/查询操作在 zip 不存在时返回空/null，不产生空文件
     * 3. 首次写入才真正创建 zip 文件
     */
    private static void demonstrateLazyInit() throws IOException {
        String freshId = "f1e2d3c4b5a69788796a5b4c3d2e1f00";
        // 清理上次运行的残留，确保该 session 为全新状态（否则 load 后 isInitialized 会因旧 zip 而为 true）
        System.out.println("\n========== 延迟初始化演示 ==========");
        try (VFS vfs = VFS.load(freshId)) {
            System.out.println("[load 之后，未写入]");
            System.out.println("  isInitialized()  = " + vfs.isInitialized());
            System.out.println("  isOpened()       = " + vfs.isOpened());
            System.out.println("  getZipFile()     = " + vfs.getZipFile());
            System.out.println("  exists(/any)     = " + vfs.exists("/any"));
            System.out.println("  listTree() 为空  = " + vfs.listTree().isEmpty());
            System.out.println("  search() size    = " + vfs.search("任意", 5).size());

            // 首次写入触发 zip 创建
            vfs.write("/data/hello.txt", "Hello Lazy");

            System.out.println("[首次写入之后]");
            System.out.println("  isInitialized()  = " + vfs.isInitialized());
            System.out.println("  isOpened()       = " + vfs.isOpened());
            System.out.println("  readString()     = " + vfs.readString("/data/hello.txt"));
            System.out.println("  listTree():\n" + vfs.listTree());
        }
        System.out.println("===================================\n");
    }

    /**
     * 演示文件操作：复制、移动、删除、递归删除
     */
    private static void demonstrateFileOps(VFS vfs) throws IOException {
        System.out.println("\n========== 文件操作演示 ==========");

        // 准备文件
        vfs.write("/ops/fileA.txt", "Content A");
        vfs.write("/ops/sub/fileB.txt", "Content B");
        System.out.println("[准备] 写入 /ops/fileA.txt 和 /ops/sub/fileB.txt");

        // 复制
        vfs.copy("/ops/fileA.txt", "/ops/fileA_copy.txt");
        System.out.println("[copy] /ops/fileA.txt -> /ops/fileA_copy.txt");
        System.out.println("  源文件存在   = " + vfs.exists("/ops/fileA.txt"));
        System.out.println("  副本存在     = " + vfs.exists("/ops/fileA_copy.txt"));
        System.out.println("  副本内容     = " + vfs.readString("/ops/fileA_copy.txt"));

        // 移动文件
        vfs.move("/ops/fileA.txt", "/ops/sub/fileA_moved.txt");
        System.out.println("[move] /ops/fileA.txt -> /ops/sub/fileA_moved.txt");
        System.out.println("  原路径存在   = " + vfs.exists("/ops/fileA.txt"));
        System.out.println("  新路径存在   = " + vfs.exists("/ops/sub/fileA_moved.txt"));

        // 移动目录
        vfs.move("/ops/sub", "/ops/renamed");
        System.out.println("[move] /ops/sub -> /ops/renamed");
        System.out.println("  /ops/sub 存在          = " + vfs.exists("/ops/sub"));
        System.out.println("  /ops/renamed 存在      = " + vfs.isDirectory("/ops/renamed"));
        System.out.println("  /ops/renamed/fileB.txt = " + vfs.readString("/ops/renamed/fileB.txt"));

        // 删除单个文件
        vfs.delete("/ops/fileA_copy.txt");
        System.out.println("[delete] /ops/fileA_copy.txt");
        System.out.println("  存在         = " + vfs.exists("/ops/fileA_copy.txt"));

        // 递归删除目录
        vfs.deleteRecursively("/ops/renamed");
        System.out.println("[deleteRecursively] /ops/renamed");
        System.out.println("  /ops/renamed 存在 = " + vfs.exists("/ops/renamed"));

        // 删除不存在的文件 -> 抛 NoSuchFileException
        try {
            vfs.delete("/ops/not_exist.txt");
        } catch (NoSuchFileException e) {
            System.out.println("[delete 不存在] 正确抛出 NoSuchFileException: " + e.getFile());
        }

        // deleteIfExists 不存在时返回 false
        boolean deleted = vfs.deleteIfExists("/ops/not_exist.txt");
        System.out.println("[deleteIfExists 不存在] 返回 = " + deleted);

        System.out.println("================================\n");
    }

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  VFS ZipFS 虚拟文件系统示例");
        System.out.println("========================================\n");

        try {
            // 0. 清理上次运行的残留数据
//            Path dataDir = Paths.get("./data/vfs-example");
//            if (Files.exists(dataDir)) {
//                try (java.util.stream.Stream<Path> walk = Files.walk(dataDir)) {
//                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
//                        try { Files.delete(p); } catch (IOException ignored) {}
//                    });
//                }
//            }

            // 1. 初始化
            WorkspaceConfig config = new WorkspaceConfig();
            config.setDataDir("./data/vfs-example");
            config.setLuceneEnabled(true);
            VFS.init(config);
            System.out.println("初始化完成，数据目录: " + config.getDataDir());
            System.out.println("Lucene 索引启用: " + config.isLuceneEnabled());

            // ===== 延迟初始化演示（使用全新 session，确保无残留）=====
            demonstrateLazyInit();

            try (VFS vfs = VFS.load(sessionId)) {

                System.out.println("[会话信息]");
                System.out.println("  sessionId    = " + sessionId);
                System.out.println("  zipFile      = " + vfs.getZipFile());
                System.out.println("  isInitialized= " + vfs.isInitialized());

                // 2. 基本读写
                vfs.write("/data/hello.txt", "Hello VFS");
                String content = vfs.readString("/data/hello.txt");
                System.out.println("\n[读写] /data/hello.txt = " + content);
                System.out.println("  size()       = " + vfs.size("/data/hello.txt") + " bytes");
                System.out.println("  exists()     = " + vfs.exists("/data/hello.txt"));
                System.out.println("  isRegularFile= " + vfs.isRegularFile("/data/hello.txt"));
                System.out.println("  isDirectory()= " + vfs.isDirectory("/data/hello.txt"));

                // 读取不存在的文件 -> 抛 NoSuchFileException
                try {
                    vfs.readString("/data/not_exist.txt");
                } catch (NoSuchFileException e) {
                    System.out.println("[读取不存在] 正确抛出 NoSuchFileException: " + e.getFile());
                }

                // 3. 添加带分段文档
                byte[] pdfBytes = "%PDF-1.4 模拟PDF内容".getBytes(StandardCharsets.UTF_8);
                List<String> pdfChunks = Arrays.asList(
                        "# 季度销售报告\n\n本季度销售额达到 1000 万元，同比增长 20%。",
                        "## 产品分析\n\nA产品销售额 500 万，B产品销售额 300 万。",
                        "## 总结\n\n整体业绩良好，建议继续加大市场投入。"
                );
                String docId = vfs.addDocument("Q3销售报告.md", pdfBytes, "/docs/reports", pdfChunks);
                System.out.println("\n[addDocument] docId = " + docId);

                String md = "# API 使用指南\n\n## 认证\n\n使用 Bearer Token 进行认证。";
                vfs.addDocument("API指南.md", null, "/docs/guides", Collections.singletonList(md));
                System.out.println("[addDocument] API指南.md (sourceBytes=null, 用文本填充)");

                // 4. 目录树
                System.out.println("\n[目录树]\n" + vfs.listTree());

                // 5. 列目录
                System.out.println("[list /docs]");
                vfs.list("/docs").forEach(name -> System.out.println("  " + name));

                // 6. 创建目录
                vfs.createDirectories("/empty/dir/nested");
                System.out.println("\n[createDirectories] /empty/dir/nested");
                System.out.println("  isDirectory(/empty/dir/nested) = " + vfs.isDirectory("/empty/dir/nested"));

                // 7. 搜索（Lucene 全文检索）
                System.out.println("\n[search 销售报告]");
                List<SearchResult> results = vfs.search("销售报告", 10);
                for (SearchResult r : results) {
                    System.out.println("  - filePath=" + r.getFilePath()
                            + " fileName=" + r.getFileName()
                            + " (score=" + r.getScore() + ", " + r.getSource() + ")");
                }

                System.out.println("\n[search 认证]");
                List<SearchResult> results2 = vfs.search("认证", 10);
                for (SearchResult r : results2) {
                    System.out.println("  - filePath=" + r.getFilePath()
                            + " fileName=" + r.getFileName()
                            + " (score=" + r.getScore() + ")");
                }

                // 8. Grep（正则表达式）
                System.out.println("\n[grep 销售额|认证]");
                List<SearchResult> grepResults = vfs.grep("销售额|认证", 10);
                for (SearchResult r : grepResults) {
                    System.out.println("  - filePath=" + r.getFilePath()
                            + " fileName=" + r.getFileName()
                            + " (" + r.getSource() + ")");
                }

                // 9. 文件操作演示
                demonstrateFileOps(vfs);

                // 10. 最终目录树
                System.out.println("[最终目录树]\n" + vfs.listTree());
            }

            System.out.println("========================================");
            System.out.println("  所有示例运行成功！");
            System.out.println("========================================");

        } catch (Exception e) {
            System.err.println("示例运行失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
