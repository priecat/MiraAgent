package net.itzq.mira.modules.workspace;

import net.itzq.mira.modules.workspace.model.Document;
import net.itzq.mira.modules.workspace.model.KBInfo;
import net.itzq.mira.modules.workspace.model.SearchResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Workspace 示例类 - 演示磁盘文件存储版工作空间的完整功能
 *
 * @author tangzq
 */
public class WorkspaceExample {

    static String sessionId = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4";

    /**
     * 演示延迟初始化：
     * 1. Workspace.load 后未初始化（磁盘无目录）
     * 2. 路径可获取、统计为空、搜索为空
     * 3. 首次写入（上传）后才真正创建磁盘目录
     */
    private static void demonstrateLazyInit() throws IOException {
        String freshId = "f1e2d3c4b5a69788796a5b4c3d2e1f00";
        System.out.println("\n========== 延迟初始化演示 ==========");
        try (Workspace wk = Workspace.load(freshId)) {
            System.out.println("[load 之后，未上传]");
            System.out.println("  isInitialized()        = " + wk.isInitialized());
            KBInfo before = wk.getInfo();
            System.out.println("  getInfo().initialized  = " + before.isInitialized());
            System.out.println("  getInfo().文件数       = " + before.getTotalDocuments());
            System.out.println("  getInfo().文件夹数     = " + before.getTotalDirectories());
            // 路径仍可获取（实际不存在）
            System.out.println("  toRealPath(/data/x.txt)= " + wk.toRealPath("/data/x.txt"));
            System.out.println("  搜索(空) size          = " + wk.search("任意", 5).size());

            // 首次写入（真正使用）触发初始化
            wk.write("/data/hello.txt", "Hello Lazy");

            System.out.println("[首次写入之后]");
            System.out.println("  isInitialized()        = " + wk.isInitialized());
            KBInfo after = wk.getInfo();
            System.out.println("  getInfo().initialized  = " + after.isInitialized());
            System.out.println("  getInfo().文件数       = " + after.getTotalDocuments());
            System.out.println("  getInfo().文件夹数     = " + after.getTotalDirectories());
        }
        System.out.println("===================================\n");
    }


    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  Workspace 磁盘文件工作空间示例");
        System.out.println("========================================\n");

        try {
            // 1. 初始化
            WorkspaceConfig config = new WorkspaceConfig();
            config.setDataDir("./data/workspace-example");
            config.setMapDir("/workspace");   // 配置映射根目录（可选，不配置时映射路径==虚拟路径）
            Workspace.init(config);
            System.out.println("初始化完成，数据目录: " + config.getDataDir());

            // ===== 延迟初始化演示（使用一个全新 session，确保磁盘无残留）=====
            demonstrateLazyInit();

            try (Workspace vk = Workspace.load(sessionId)) {

                // ===== 三路径概念：虚拟 / 真实 / 映射 =====
                System.out.println("\n[三路径根目录]");
                System.out.println("  虚拟根  getVirtualRoot()  = " + vk.getVirtualRoot());
                System.out.println("  真实根  getRealStorageRoot() = " + vk.getRealStorageRoot());
                System.out.println("  映射根  getMappedRoot()  = " + vk.getMappedRoot());

                // 2. 兼容写入
                vk.write("/data/hello.txt", "Hello");
                String content = vk.readString("/data/hello.txt");
                System.out.println("\n[读写] /data/hello.txt = " + content);
                System.out.println("  虚拟路径 getFilePath()  = /data/hello.txt");
                System.out.println("  真实路径 toRealPath()   = " + vk.toRealPath("/data/hello.txt"));
                System.out.println("  映射路径 toMappedPath() = " + vk.toMappedPath("/data/hello.txt"));

                // 3. 添加带分段文档
                byte[] pdfBytes = "%PDF-1.4 模拟PDF内容".getBytes(StandardCharsets.UTF_8);
                List<String> pdfChunks = Arrays.asList(
                        "# 季度销售报告\n\n本季度销售额达到 1000 万元，同比增长 20%。",
                        "## 产品分析\n\nA产品销售额 500 万，B产品销售额 300 万。",
                        "## 总结\n\n整体业绩良好，建议继续加大市场投入。"
                );
                vk.addDocument("Q3销售报告.md", pdfBytes, "/docs/reports", pdfChunks);

                String md = "# API 使用指南\n\n## 认证\n\n使用 Bearer Token 进行认证。";
                vk.addDocument("API指南.md", null, "/docs/guides", Collections.singletonList(md));

                // 4. 目录树
                System.out.println("\n[目录树]\n" + vk.listTree());

                // 5. 列目录
                System.out.println("[list /docs]");
                vk.list("/docs").forEach(name -> System.out.println("  " + name));

                // 6. 搜索
                System.out.println("\n[search 销售报告]");
                List<SearchResult> results = vk.search("销售报告", 10);
                for (SearchResult r : results) {
                    System.out.println("  - 虚拟=" + r.getFilePath()
                            + " 真实=" + r.getRealPath()
                            + " 映射=" + r.getMappedPath()
                            + " (score=" + r.getScore() + ", " + r.getSource() + ")");
                }

                // 7. Grep
                System.out.println("\n[grep 销售额|认证]");
                List<SearchResult> grepResults = vk.grep("销售额|认证", 10);
                for (SearchResult r : grepResults) {
                    System.out.println("  - 虚拟=" + r.getFilePath()
                            + " 真实=" + r.getRealPath()
                            + " 映射=" + r.getMappedPath());
                }

                // 8. 统计
                KBInfo info = vk.getInfo();
                System.out.println("\n[统计] 文档数=" + info.getTotalDocuments()
                        + ", 源文件总大小=" + info.getSourceTotalSize());

                // 9. 列出所有文档（含三种路径）
                System.out.println("\n[所有文档]");
                for (Document doc : vk.getKB().listDocuments()) {
                    System.out.println("  - 虚拟=" + doc.getFilePath()
                            + " 真实=" + doc.getRealPath()
                            + " 映射=" + doc.getMappedPath()
                            + " (" + doc.getSourceSize() + " bytes)");
                }

                // 10. 目录对象的三路径
                System.out.println("\n[/docs 目录对象]");
                net.itzq.mira.modules.workspace.model.Directory docsDir = vk.getDirectory("/docs");
                if (docsDir != null) {
                    System.out.println("  虚拟=" + docsDir.getDirPath()
                            + " 真实=" + docsDir.getRealPath()
                            + " 映射=" + docsDir.getMappedPath());
                }
            }

            System.out.println("\n========================================");
            System.out.println("  所有示例运行成功！");
            System.out.println("========================================");

        } catch (Exception e) {
            System.err.println("示例运行失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
