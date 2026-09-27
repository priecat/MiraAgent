package net.itzq.mira.modules.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.itzq.mira.core.utils.IdGen;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.agent.AutoAgent;
import net.itzq.mira.modules.runtime.KernelRuntime;
import net.itzq.mira.modules.vfs.model.Document;
import net.itzq.mira.modules.vfs.model.KBInfo;
import net.itzq.mira.modules.vfs.model.SearchResult;
import net.itzq.mira.modules.workspace.FileWorkspace;
import net.itzq.mira.modules.workspace.LocalFileSpace;
import net.itzq.mira.modules.workspace.Workspace;
import net.itzq.mira.modules.workspace.WorkspaceConfig;

/**
 * 工作空间（Workspace）示例 —— 多实例写法。
 *
 * <h3>"工作空间"的两层含义</h3>
 * <ol>
 *   <li><b>会话工作目录 {@code workspacePath}</b>：给会话绑定一个真实项目目录，AI 的
 *       Bash / Read / Write / Edit / Glob / Grep 都被约束在该目录内（见 {@link #bindWorkspacePath}）。</li>
 *   <li><b>内核文件存储抽象 {@code Workspace}</b>：把文件存进内核管理的存储位置。三种实现：
 *       <ul>
 *         <li>{@link FileWorkspace}：会话目录形态，落盘到 {@code <dataDir>/ab/cd/<sessionId>/storage}，
 *             带"虚拟/真实/映射"三路径与文档统计——本示例演示它；</li>
 *         <li>{@link net.itzq.mira.modules.vfs.VFS}：会话附件，内容落一个 zip（见 {@code VfsExample}）；</li>
 *         <li>{@link LocalFileSpace}：以任意真实目录为根。</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>为什么用 {@code rt.workspace().load(...)}</h3>
 * <p>{@code FileWorkspace} 的静态 {@code init(config)} 是进程级一次性（多实例会互相干扰）。
 * 现在它有了实例入口：{@code FileWorkspace.of(config, sessionId)}，并由
 * {@code KernelRuntime.workspace()} 以"数据目录跟随运行时"的方式暴露（与 {@code rt.vfs()} 对称）。
 */
public class WorkspaceExample {

    /** 32 位十六进制会话 id（协议要求，无连字符） */
    static final String SESSION_ID = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4";

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("  Workspace 磁盘工作空间示例");
        System.out.println("========================================\n");

        // 显式给定工作空间配置（dataDir 落盘根 + mapDir 展示用映射根）
        WorkspaceConfig wc = new WorkspaceConfig();
        wc.setDataDir("./data/workspace-example");
        wc.setMapDir("/workspace");

        KernelRuntime rt = KernelRuntime.builder()
                .name("workspace-example")
                .dataDir("./data/workspace-example")
                .vfsConfig(wc)
                .build()
                .start();
        try {
            // ========== 磁盘工作空间（会话目录形态，多实例） ==========
            try (FileWorkspace vk = rt.workspace().load(SESSION_ID)) {
                threePathsAndReadWrite(vk);
                documentsAndSearch(vk);
                demonstrateFileOps(vk);
                System.out.println("\n[最终目录树]\n" + vk.listTree());
            }

            // ========== 另一层含义：给会话绑定 workspacePath ==========
            Path projectDir = Files.createTempDirectory("mira-workspace-project");
            bindWorkspacePath(rt, projectDir.toString());

            // ========== 以任意真实目录为根（LocalFileSpace） ==========
            localFileSpaceDemo(projectDir);
        } finally {
            rt.close();
        }

        System.out.println("\n========================================");
        System.out.println("  所有示例运行成功！");
        System.out.println("========================================");
    }

    /** 三路径（虚拟/真实/映射）+ 基本读写 */
    private static void threePathsAndReadWrite(FileWorkspace vk) throws IOException {
        System.out.println("========== 三路径与基本读写 ==========");
        System.out.println("  sessionId           = " + SESSION_ID);
        System.out.println("  虚拟根 getVirtualRoot()      = " + vk.getVirtualRoot());
        System.out.println("  真实根 getRealStorageRoot()  = " + vk.getRealStorageRoot());
        System.out.println("  映射根 getMappedRoot()       = " + vk.getMappedRoot());

        vk.write("/data/hello.txt", "Hello");
        System.out.println("\n  readString(/data/hello.txt)  = " + vk.readString("/data/hello.txt"));
        System.out.println("  真实路径 toRealPath()        = " + vk.toRealPath("/data/hello.txt"));
        System.out.println("  映射路径 toMappedPath()      = " + vk.toMappedPath("/data/hello.txt"));
        System.out.println("  size() = " + vk.size("/data/hello.txt") + " bytes"
                + ", exists() = " + vk.exists("/data/hello.txt")
                + ", isRegularFile() = " + vk.isRegularFile("/data/hello.txt"));

        try {
            vk.readString("/data/not_exist.txt");
        } catch (NoSuchFileException e) {
            System.out.println("  [读取不存在] 正确抛出 NoSuchFileException: " + e.getFile());
        }
        System.out.println();
    }

    /** 落文档 + 统计 + 列目录 + Grep */
    private static void documentsAndSearch(FileWorkspace vk) throws IOException {
        System.out.println("========== 文档与检索 ==========");
        String docId = vk.addDocument("Q3销售报告.md",
                "%PDF-1.4 模拟PDF内容".getBytes(StandardCharsets.UTF_8),
                "/docs/reports",
                Arrays.asList(
                        "# 季度销售报告\n\n本季度销售额达到 1000 万元，同比增长 20%。",
                        "## 产品分析\n\nA产品销售额 500 万，B产品销售额 300 万。"));
        System.out.println("  [addDocument] docId = " + docId);
        vk.addDocument("API指南.md", null, "/docs/guides",
                Collections.singletonList("# API 使用指南\n\n使用 Bearer Token 进行认证。"));

        System.out.println("  [列目录 /docs]");
        try (Stream<String> list = vk.list("/docs")) {
            list.collect(Collectors.toList()).forEach(name -> System.out.println("    " + name));
        }

        KBInfo info = vk.getInfo();
        System.out.println("  [统计] 文档数=" + info.getTotalDocuments()
                + ", 源文件总大小=" + info.getSourceTotalSize());

        System.out.println("  [grep 销售额|认证]");
        for (SearchResult r : vk.grep("销售额|认证", 10)) {
            System.out.println("    - 虚拟=" + r.getFilePath()
                    + " 真实=" + r.getRealPath()
                    + " 映射=" + r.getMappedPath());
        }
        System.out.println("  [/docs 目录对象]");
        net.itzq.mira.modules.vfs.model.Directory docsDir = vk.getDirectory("/docs");
        if (docsDir != null) {
            System.out.println("    虚拟=" + docsDir.getDirPath()
                    + " 真实=" + docsDir.getRealPath()
                    + " 映射=" + docsDir.getMappedPath());
        }
        System.out.println("  [所有文档]");
        for (Document doc : vk.listDocuments()) {
            System.out.println("    - 虚拟=" + doc.getFilePath() + " (" + doc.getSourceSize() + " bytes)");
        }
        System.out.println();
    }

    /** 文件操作：复制、移动、删除、递归删除 */
    private static void demonstrateFileOps(FileWorkspace vk) throws IOException {
        System.out.println("========== 文件操作演示 ==========");

        vk.write("/ops/fileA.txt", "Content A");
        vk.write("/ops/sub/fileB.txt", "Content B");

        vk.copy("/ops/fileA.txt", "/ops/fileA_copy.txt");
        System.out.println("[copy] 源存在=" + vk.exists("/ops/fileA.txt")
                + " 副本存在=" + vk.exists("/ops/fileA_copy.txt")
                + " 副本内容=" + vk.readString("/ops/fileA_copy.txt"));

        vk.move("/ops/fileA.txt", "/ops/sub/fileA_moved.txt");
        System.out.println("[move] 原路径存在=" + vk.exists("/ops/fileA.txt")
                + " 新路径存在=" + vk.exists("/ops/sub/fileA_moved.txt"));

        vk.move("/ops/sub", "/ops/renamed");
        System.out.println("[move 目录] /ops/sub 存在=" + vk.exists("/ops/sub")
                + " /ops/renamed 是目录=" + vk.isDirectory("/ops/renamed")
                + " 内容=" + vk.readString("/ops/renamed/fileB.txt"));

        vk.delete("/ops/fileA_copy.txt");
        System.out.println("[delete] 存在=" + vk.exists("/ops/fileA_copy.txt"));

        vk.deleteRecursively("/ops/renamed");
        System.out.println("[deleteRecursively] /ops/renamed 存在=" + vk.exists("/ops/renamed"));

        try {
            vk.delete("/ops/not_exist.txt");
        } catch (NoSuchFileException e) {
            System.out.println("[delete 不存在] 正确抛出 NoSuchFileException: " + e.getFile());
        }
        System.out.println("[deleteIfExists 不存在] 返回 = " + vk.deleteIfExists("/ops/not_exist.txt"));
    }

    /** 以任意真实目录为根：LocalFileSpace（实例级，无全局状态） */
    private static void localFileSpaceDemo(Path projectDir) throws IOException {
        System.out.println("\n========== LocalFileSpace（真实目录为根）==========");
        try (Workspace wk = LocalFileSpace.open(projectDir.toString())) {
            wk.write("/notes/todo.md", "待办：补测试");
            System.out.println("  根路径   = " + ((LocalFileSpace) wk).getRootPath());
            System.out.println("  读回内容 = " + wk.readString("/notes/todo.md"));
            System.out.println("  目录树:\n" + wk.listTree());
        }
    }

    /**
     * 给会话绑定 {@code workspacePath}：AI 的代码类工具被约束在该目录内。
     *
     * <p>本段只演示**装配**（只读工具集 + 真实目录绑定），不发模型请求。
     */
    private static void bindWorkspacePath(KernelRuntime rt, String projectDir) {
        System.out.println("\n========== 会话工作目录（workspacePath）==========");

        AgentContextHolder holder = AgentContextHolder.builder()
                .runtime(rt)
                .userId("u-example")
                .historyId(IdGen.uuid())
                .modelAlias("your-model-alias")
                .prompt("你是一个严谨的编程助手，改动前后都要说明原因。")
                // 关键：AI 能碰的真实项目目录
                .workspacePath(projectDir)
                .build();

        AutoAgent agent = new AutoAgent(holder, "code-assistant");
        // 只读工具集：不含 Bash / Edit / Write，AI 只能看不能改
        agent.mountReadOnlyTools();

        System.out.println("  workspacePath = " + holder.getWorkspacePath());
        System.out.println("  挂载工具      = " + holder.getTools());
        System.out.println("  只读？        = " + AutoAgent.isReadOnly(holder.getTools()));
        System.out.println("  （换成 agent.mountFullCodeTools() 即放开读写与 Bash）");
    }
}
