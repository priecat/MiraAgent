package net.itzq.mira.modules.vkb;

import net.itzq.mira.modules.vkb.model.Document;
import net.itzq.mira.modules.vkb.model.KBInfo;
import net.itzq.mira.modules.vkb.model.SearchResult;
import net.itzq.mira.modules.vkb.provider.EmbeddingProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * VKB 示例类 - 完整演示所有功能
 *
 * 可作为测试使用，静态调用方式展示 VKB 的完整流程
 *
 * @author tangzq
 */
public class VKBExample {

    static  String sessionId = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4";

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  VKB 轻量虚拟工作空间示例");
        System.out.println("========================================\n");

        try {
            // 1. 初始化 VKB
            exampleInit();

            // 2. 添加文档（带分段）
//            exampleAddDocuments();

            // 3. 文件系统操作
            exampleFileSystem();

            // 4. 搜索功能
            exampleSearch();
//
//            // 5. Grep 搜索
            exampleGrep();
//
//            // 6. 文件读取
            exampleFileRead();
//
//            // 7. 获取统计信息
            exampleInfo();

            System.out.println("\n========================================");
            System.out.println("  所有示例运行成功！");
            System.out.println("========================================");

        } catch (Exception e) {
            System.err.println("示例运行失败: " + e.getMessage());
            e.printStackTrace();
        } finally {

        }
    }

    // ==================== 1. 初始化 ====================

    static void exampleInit() {
        System.out.println("=== 1. 初始化 VKB ===\n");

        // 创建配置
        VKBConfig config = new VKBConfig();
        config.setDataDir("./data/vkb-example");

        // 初始化
        VKB.init(config);
        System.out.println("VKB 初始化完成");
        System.out.println("数据目录: " + config.getDataDir());
        System.out.println();
    }

    // ==================== 2. 添加文档 ====================

    static void exampleAddDocuments() {
        System.out.println("=== 2. 添加文档（带分段）===\n");


        VKB vk = VKB.load(sessionId);

        // 示例1：添加 PDF 文档（模拟源文件 + 分段）
        byte[] pdfBytes = "%PDF-1.4 模拟PDF内容".getBytes(StandardCharsets.UTF_8);
        List<String> pdfChunks = Arrays.asList(
                "# 季度销售报告\n\n本季度销售额达到 1000 万元，同比增长 20%。",
                "## 产品分析\n\nA产品销售额 500 万，B产品销售额 300 万，C产品销售额 200 万。",
                "## 区域分布\n\n华东地区占比 40%，华南地区占比 30%，华北地区占比 30%。",
                "## 总结\n\n整体业绩良好，建议继续加大市场投入。"
        );

        String docId1 = vk.addDocument("Q3销售报告.pdf", pdfBytes, "/docs/reports", pdfChunks);
        System.out.println("添加文档: Q3销售报告.pdf -> " + docId1);
        System.out.println("  虚拟路径: /docs/reports/Q3销售报告.md");
        System.out.println("  分段数量: " + pdfChunks.size());

        // 示例2：添加纯文本（无需源文件）
        String markdownContent = "# API 使用指南\n\n" +
                "## 认证\n\n使用 Bearer Token 进行认证。\n\n" +
                "## 接口列表\n\n" +
                "### GET /api/users\n\n获取用户列表。\n\n" +
                "### GET /api/mall\n\n获取销售列表。\n\n" +
                "### POST /api/users\n\n创建新用户。";

        String docId2 = vk.addDocument("API指南.md",null,"/docs/guides", Collections.singletonList(markdownContent));
        System.out.println("\n添加文本: /docs/guides/API指南.md -> " + docId2);

        // 示例3：添加更多文档
        List<String> techChunks = Arrays.asList(
                "# 微服务架构设计\n\n微服务是一种架构风格，将应用拆分为小型服务。",
                "## 服务拆分原则\n\n按业务领域拆分，每个服务独立部署。",
                "## 通信方式\n\n推荐使用异步消息队列进行服务间通信。"
        );

        String docId3 = vk.addDocument("微服务架构.docx", null, "/docs/tech", techChunks);
        System.out.println("\n添加文档: 微服务架构.docx -> " + docId3);

        // 列出所有文档
        List<Document> docs = vk.getKB().listDocuments();
        System.out.println("\n当前虚拟工作空间文档数: " + docs.size());
        for (Document doc : docs) {
            System.out.println("  - " + doc.getFilePath() + " (" + doc.getChunkCount() + " 分段)");
        }
        System.out.println();
    }

    // ==================== 3. 文件系统操作 ====================

    static void exampleFileSystem() throws Exception {
        System.out.println("=== 3. 文件系统操作 ===\n");

        VKB vk = VKB.load(sessionId);
        FileSystem fs = vk.getFileSystem();

        // 列出根目录
        System.out.println("根目录内容:");
        Path root = fs.getPath("/");
        Files.newDirectoryStream(root).forEach(entry -> {
            System.out.println("  " + entry.getFileName());
        });

        // 列出 /docs 目录
        System.out.println("\n/docs 目录内容:");
        Path docs = fs.getPath("/docs");
        Files.newDirectoryStream(docs).forEach(entry -> {
            System.out.println("  " + entry.getFileName());
        });

        // 列出 /docs/reports 目录
        System.out.println("\n/docs/reports 目录内容:");
        Path reports = fs.getPath("/docs/reports");
        Files.newDirectoryStream(reports).forEach(entry -> {
            System.out.println("  " + entry.getFileName());
        });

        // 检查文件是否存在
        Path testFile = fs.getPath("/docs/reports/Q3销售报告.pdf");
        System.out.println("\n文件存在检查:");
        System.out.println("  /docs/reports/Q3销售报告.pdf -> " + Files.exists(testFile));

        Path notExist = fs.getPath("/not/exist.md");
        System.out.println("  /not/exist.md -> " + Files.exists(notExist));

        System.out.println();
    }

    // ==================== 4. 搜索功能 ====================

    static void exampleSearch() {
        System.out.println("=== 4. 搜索功能 ===\n");

        VKB vk = VKB.load(sessionId);

        // 搜索相关文件
        String query = "销售报告";
        System.out.println("搜索: '" + query + "'");
        List<SearchResult> results = vk.search(query,10);

        if (results.isEmpty()) {
            System.out.println("  (无结果)");
        } else {
            for (SearchResult result : results) {
                System.out.println("  - " + result.getFilePath());
                System.out.println("    相似度: " + result.getScore());
                System.out.println("    来源: " + result.getSource());
            }
        }

        System.out.println();
    }

    // ==================== 5. Grep 搜索 ====================

    static void exampleGrep() {
        System.out.println("=== 5. Grep 搜索 ===\n");

        VKB vk = VKB.load(sessionId);

        // Grep 搜索
        String regex = "销售额|销售报告";
        System.out.println("Grep: '" + regex + "'");
        List<SearchResult> grepResults = vk.grep(regex,10);

        if (grepResults.isEmpty()) {
            System.out.println("  (无结果)");
        } else {
            for (SearchResult result : grepResults) {
                System.out.println("  - " + result.getFilePath());
            }
        }

        System.out.println();
    }

    // ==================== 6. 文件读取 ====================

    static void exampleFileRead() throws Exception {
        System.out.println("=== 6. 文件读取 ===\n");

        VKB vk = VKB.load(sessionId);
        FileSystem fs = vk.getFileSystem();

        // 读取文件内容
        Path filePath = fs.getPath("/docs/reports/Q3销售报告.pdf");
        if (Files.exists(filePath)) {
            byte[] bytes = Files.readAllBytes(filePath);
            String content = new String(bytes, StandardCharsets.UTF_8);

            System.out.println("文件: /docs/reports/Q3销售报告.pdf");
            System.out.println("大小: " + bytes.length + " bytes");
            System.out.println("内容预览:");
            System.out.println("---");
            // 只显示前 500 字符
            if (content.length() > 500) {
                System.out.println(content.substring(0, 500) + "...");
            } else {
                System.out.println(content);
            }
            System.out.println("---");
        }

        // 通过路径获取知识文本
        String text = vk.getKB().getKnowledgeTextByPath("/docs/guides/API指南.md");
        if (text != null) {
            System.out.println("\n文件: /docs/guides/API指南.md");
            System.out.println("大小: " + text.length() + " chars");
            System.out.println("内容预览:");
            System.out.println("---");
            if (text.length() > 300) {
                System.out.println(text.substring(0, 300) + "...");
            } else {
                System.out.println(text);
            }
            System.out.println("---");
        }

        System.out.println();
    }

    // ==================== 7. 统计信息 ====================

    static void exampleInfo() {
        System.out.println("=== 7. 统计信息 ===\n");

        VKB vk = VKB.load(sessionId);

        KBInfo info = vk.getInfo();
        System.out.println("虚拟工作空间统计:");
        System.out.println("  会话ID: " + info.getSessionId());
        System.out.println("  文档数: " + info.getTotalDocuments());
        System.out.println("  分段数: " + info.getTotalChunks());
        System.out.println("  源文件总大小: " + formatSize(info.getSourceTotalSize()));
        System.out.println("  文本总大小: " + formatSize(info.getTextTotalSize()));

        System.out.println();
    }

    // ==================== 工具方法 ====================

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
