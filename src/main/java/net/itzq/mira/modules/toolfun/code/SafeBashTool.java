package net.itzq.mira.modules.toolfun.code;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.tool.annotation.Tool;
import net.itzq.mira.modules.ai.tool.annotation.ToolParam;
import net.itzq.mira.modules.ai.tool.ToolCallResult;
import net.itzq.mira.modules.config.AgentConfig;
import net.itzq.mira.modules.config.GlobalConfigManager;
import net.itzq.mira.modules.toolfun.ToolFun;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SafeBashTool - Shell 命令执行与安全管控
 *
 * <ul>
 *   <li><b>输出取尾部</b>：只保留末尾 N 行 / M 字节（构建日志的关键信息在尾部，掉头部比掉尾部危害小）</li>
 *   <li><b>滑窗环形缓冲</b>：内存中只保留 2×上限的字节，读到即丢弃最旧的块 —— 巨量输出不会撑爆内存</li>
 *   <li><b>超限落盘</b>：一旦超过上限，把完整输出写入临时文件并把路径回给模型，需要时可完整读取</li>
 *   <li><b>单字符集解码</b>：按字节读取后 UTF-8 优先，出现替换字符时回退控制台编码（中国 Windows 常见 GBK）</li>
 *   <li><b>超时即杀</b>：到点强制终止，并给出可行动的提示</li>
 *   <li>stdin 立即关闭：命令等待交互输入时会拿到 EOF 立即结束，而不是挂到超时</li>
 *   <li>输出总量看门狗：单条命令输出超过上限时强制终止，避免刷爆磁盘/内存</li>
 * </ul>
 *
 * 安全管控：
 * - 作为“最后手段”——优先使用专用工具而非 Bash
 * - Git 安全协议：禁止 --force、--no-verify、reset --hard 等危险操作（检测并警告）
 * - sleep 阻塞检测
 * - 支持降权执行（通过 SANDBOX_USER 环境变量配置）
 *
 * 已知限制：超时/终止只作用于直接子进程，不保证回收其派生进程（Java 8 无 ProcessHandle，
 * 无法可靠地按进程组回收）。
 *
 * @author tangzq
 */
@Slf4j
public class SafeBashTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300; // 5 分钟
    private static final int MAX_TIMEOUT_SECONDS = 1800;

    /** 回给模型的输出上限（尾部保留） */
    private static final int MAX_OUTPUT_LINES = 2000;
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    /** 内存中作环形缓冲保留的字节数（2× 上限，保证尾部完整） */
    private static final int RING_KEEP_BYTES = MAX_OUTPUT_BYTES * 2;
    /** 单条命令输出总量的硬上限，超过即强制终止 */
    private static final long MAX_TOTAL_OUTPUT_BYTES = 64L * 1024 * 1024;
    private static final int WATCHDOG_INTERVAL_MS = 2000;

    // 读取环境变量中的沙箱用户名。如果设置了，Bash命令将以该用户身份执行。
    /**
     * 降权执行用户：**调用时**读取声明——
     * 原为 static final 在类加载期求值，若早于宿主应用配置则永久为空；
     * P5 多实例：优先取**本运行时**声明（经工具上下文），无上下文回落默认运行时。
     */
    private static String sandboxUser(AgentContextHolder contextHolder) {
        try {
            AgentConfig cfg = null;
            if (contextHolder != null) {
                cfg = contextHolder.getRuntime().declaration().getAgentConfig();
            }
            if (cfg == null) {
                cfg = GlobalConfigManager.config().getAgentConfig();
            }
            return cfg == null ? null : cfg.getBashSandBoxUser();
        } catch (Exception e) {
            return null;
        }
    }

    /** 危险命令模式（检测并警告） */
    private static final Pattern[] DANGEROUS_PATTERNS = {
            Pattern.compile("rm\\s+-rf\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("rm\\s+-r\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+reset\\s+--hard", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+push\\s+--force", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+push\\s+-f\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+clean\\s+-f", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+branch\\s+-D", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+checkout\\s+--", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+commit\\s+--amend", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+--no-verify", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DROP\\s+(TABLE|DATABASE|SCHEMA)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("TRUNCATE\\s+(TABLE|DATABASE)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DELETE\\s+FROM\\s+\\w+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("del\\s+/[SQ]\\s", Pattern.CASE_INSENSITIVE),     // Windows: del /S /Q
            Pattern.compile("format\\s+[A-Z]:", Pattern.CASE_INSENSITIVE),     // Windows: format C:
    };

    /** 禁止通过 Bash 调用的命令（应使用专用工具） */
    private static final Set<String> PREFER_DEDICATED_TOOL = new HashSet<>(Arrays.asList(
            "grep", "rg", "find", "ls", "cat", "head", "tail", "sed", "awk", "echo"
    ));

    /**
     * sleep 阻塞检测：匹配「命令起始处」或「紧跟 ; &amp; | ( )」的 sleep。
     * 前面的字符限定为命令分隔符，避免把 `echo sleep 5` 这类文本误判。
     */
    private static final Pattern SLEEP_PATTERN =
            Pattern.compile("(?:^|[;&|()])[ \\t]*sleep[ \\t]+(\\d+(?:\\.\\d+)?)", Pattern.MULTILINE);

    @Tool(name = ToolFun.TOOL_Bash,
          display = "执行命令",
          description = "执行 Shell 命令并返回其输出（stdout 与 stderr 合并）。\n\n"
                  + "重要: 避免使用此工具运行 find、grep、cat、head、tail、sed、awk 或 echo 命令，"
                  + "除非在验证了专用工具无法完成任务后明确指示。请使用对应的专用工具：\n"
                  + "  - 文件搜索: 使用 Glob（而非 find 或 ls）\n"
                  + "  - 内容搜索: 使用 Grep（而非 grep 或 rg）\n"
                  + "  - 读取文件: 使用 Read（而非 cat/head/tail）\n"
                  + "  - 编辑文件: 使用 Edit（而非 sed/awk）\n"
                  + "  - 写入文件: 使用 Write（而非 echo >/cat <<EOF）\n\n"
                  + "结果格式：头部给出「工作目录 / 命令 / 退出码」，随后是命令输出。\n"
                  + "  - 每次都检查「退出码」，非 0 说明失败，先定位原因再继续\n"
                  + "  - 输出过长时只保留末尾 " + MAX_OUTPUT_LINES + " 行 / " + (MAX_OUTPUT_BYTES / 1024)
                  + " KB，并给出「完整输出已保存到 <路径>」，需要全文时用 Read 读取该路径\n"
                  + "  - 每条命令开新 shell（不保留 cd / 变量 / 函数），需要切换目录请用 workdir 参数，不要用 cd\n"
                  + "  - 命令的 stdin 会被立即关闭，需要交互输入的命令无法使用\n\n"
                  + "Git 安全协议:\n"
                  + "- 永远不要更新 git config\n"
                  + "- 除非用户明确要求，永远不要执行破坏性 git 命令"
                  + "（push --force、reset --hard、checkout . 等）\n"
                  + "- 不要使用带 -i 标志的 git 命令（交互式）\n"
                  + "- 除非明确要求，不要跳过 hooks（--no-verify、--no-gpg-sign）\n"
                  + "- 避免 sleep 等阻塞命令"
    )
    public String bash(
            @ToolParam(description = "要执行的 Shell 命令（必填）") String command,
            @ToolParam(description = "命令用途的一句话说明（active voice，5-10 个词），用于界面展示",
                       required = false) String description,
            @ToolParam(description = "超时秒数，默认 300（5 分钟），上限 1800", required = false) Integer timeout,
            @ToolParam(description = "工作目录：绝对路径，或相对当前工作空间的相对路径；默认使用当前工作空间",
                       required = false) String workdir,
            AgentContextHolder contextHolder) {

        try {
            if (StringUtils.isBlank(command)) {
                return ToolCallResult.error("错误：command 不能为空。");
            }

            String workspacePath = contextHolder.getWorkspacePath();
            if (StringUtils.isBlank(workspacePath)) {
                return ToolCallResult.error("错误：当前未设置工作空间路径，禁用 Bash 工具。");
            }

            String resolvedWorkDir = resolveWorkdir(workspacePath, workdir);
            if (resolvedWorkDir == null) {
                return ToolCallResult.error("错误：workdir 不是已存在的目录 -> " + workdir);
            }

            log.info("bash WorkDir: " + resolvedWorkDir);

            int timeoutSec = (timeout == null || timeout <= 0)
                    ? DEFAULT_TIMEOUT_SECONDS
                    : Math.min(timeout, MAX_TIMEOUT_SECONDS);

            // 检查是否应使用专用工具（仅提示，不阻断）
            for (String preferred : PREFER_DEDICATED_TOOL) {
                if (command.trim().startsWith(preferred + " ") || command.trim().equals(preferred)) {
                    log.warn("BashTool: 检测到 {} 命令，应优先使用对应的专用工具", preferred);
                    break;
                }
            }

            // 危险命令检测
            String warning = checkDangerousCommand(command);
            if (warning != null) {
                log.warn("BashTool: 检测到危险命令模式 —— {}", warning);
            }

            // sleep 阻塞检测
            String sleepHit = detectBlockedSleep(command);
            if (sleepHit != null) {
                return ToolCallResult.error(String.format(
                        "已阻止: 检测到阻塞式 sleep %s 秒。如确需延迟（限速/节流），请控制在 2 秒以内；"
                                + "长时间等待请改用带超时的命令，或先启动再在后续步骤中检查结果。",
                        sleepHit));
            }

            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb = buildProcessBuilder(command, resolvedWorkDir, isWindows, contextHolder);

            pb.environment().put("GIT_EDITOR", "true");
            // stderr 合并进 stdout，保证输出顺序可信
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 立即关闭 stdin：等待交互输入的命令会拿到 EOF 立即结束，而不是挂到超时
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // 关闭失败不致命
            }

            // 输出收集：滑窗环形缓冲 + 超限落盘
            final ReadResult read = new ReadResult();
            Thread readerThread = new Thread(() -> read.pump(process), "bash-output-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            // 输出总量看门狗
            final AtomicBoolean killedByWatchdog = new AtomicBoolean(false);
            Thread watchdog = new Thread(() -> {
                while (process.isAlive()) {
                    try {
                        Thread.sleep(WATCHDOG_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (read.totalBytes.get() > MAX_TOTAL_OUTPUT_BYTES) {
                        killedByWatchdog.set(true);
                        process.destroyForcibly();
                        return;
                    }
                }
            }, "bash-output-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            watchdog.interrupt();

            if (!finished) {
                process.destroyForcibly();
                readerThread.join(2000);
                String partial = read.renderTail();
                return ToolCallResult.error(String.format(
                        "命令执行超时 (%d 秒)，已强制终止。若该命令确实需要更长时间（且不是在等待交互输入），"
                                + "请用更大的 timeout 重试。\n工作目录: %s\n命令: %s\n\n%s",
                        timeoutSec, resolvedWorkDir, command,
                        partial.isEmpty() ? "(无输出)" : partial));
            }

            readerThread.join(2000);
            int exitCode = process.exitValue();

            StringBuilder result = new StringBuilder();
            result.append(String.format("工作目录: %s\n", resolvedWorkDir));
            result.append(String.format("命令: %s\n", command));
            if (StringUtils.isNotBlank(description)) {
                result.append(String.format("说明: %s\n", description.trim()));
            }
            result.append(String.format("退出码: %d\n\n", exitCode));

            if (warning != null) {
                result.append(String.format("！！！警告: %s\n\n", warning));
            }
            if (killedByWatchdog.get()) {
                result.append(String.format("！！！警告: 输出超过 %d MB 上限，命令已被强制终止，输出可能不完整\n\n",
                        MAX_TOTAL_OUTPUT_BYTES / (1024 * 1024)));
            }

            result.append(read.renderTail());

            if (read.truncated) {
                if (read.spillPath != null) {
                    result.append(String.format(
                            "\n\n[输出已截断: 仅保留末尾 %d 行 / %d KB；完整输出已保存到 %s，需要全文可用 Read 读取]",
                            MAX_OUTPUT_LINES, MAX_OUTPUT_BYTES / 1024, read.spillPath));
                } else {
                    result.append(String.format(
                            "\n\n[输出已截断: 仅保留末尾 %d 行 / %d KB（完整输出落盘失败）]",
                            MAX_OUTPUT_LINES, MAX_OUTPUT_BYTES / 1024));
                }
            }

            return ToolCallResult.of(exitCode == 0, result.toString());

        } catch (Exception e) {
            log.error("BashTool 执行失败", e);
            return ToolCallResult.error("命令执行失败: " + e.getMessage());
        }
    }

    // ==================================================================
    // 进程构建
    // ==================================================================

    /** 构建 ProcessBuilder，区分 Windows / 普通 / 降权三种执行模式 */
    private ProcessBuilder buildProcessBuilder(String command, String workDir, boolean isWindows,
            AgentContextHolder contextHolder) {
        if (isWindows) {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", command);
            pb.directory(new File(workDir));
            return pb;
        }
        String sandboxUser = sandboxUser(contextHolder);
        if (StringUtils.isNotBlank(sandboxUser)) {
            // === 降权执行模式 ===
            log.info("BashTool: 启用降权执行，目标用户: {}", sandboxUser);

            // 1. 包装环境变量（防止 su 重置环境）
            // 2. 包装工作目录切换（防止 agent_user 无权访问原工作目录报错）
            // 注意：这里使用 su -s /bin/sh 强制指定shell，防止 agent_user 被设置为 /sbin/nologin
            String escapedWorkDir = workDir.replace("\"", "\\\"");
            String wrappedCommand = String.format(
                    "export GIT_EDITOR=true; cd \"%s\" 2>/dev/null || cd /tmp; %s",
                    escapedWorkDir, command
            );

            ProcessBuilder pb = new ProcessBuilder("su", "-s", "/bin/sh", sandboxUser, "-c", wrappedCommand);
            // 注意：降权模式下不能使用 pb.directory(workDir)。
            // 因为如果 agent_user 没有该目录的读取/执行权限，ProcessBuilder 在启动进程时会直接抛出 IOException。
            // 所以我们将其设为根目录，实际的 cd 已在 wrappedCommand 中处理。
            pb.directory(new File("/"));
            return pb;
        }
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.directory(new File(workDir));
        return pb;
    }

    /** 解析工作目录：绝对路径直接用；相对路径基于工作空间解析；目录不存在返回 null */
    private String resolveWorkdir(String workspacePath, String workdir) {
        if (StringUtils.isBlank(workdir)) {
            return workspacePath;
        }
        Path base = Paths.get(workspacePath);
        Path resolved = Paths.get(workdir.trim());
        if (!resolved.isAbsolute()) {
            resolved = base.resolve(workdir.trim());
        }
        resolved = resolved.toAbsolutePath().normalize();
        return Files.isDirectory(resolved) ? resolved.toString() : null;
    }

    // ==================================================================
    // 输出收集
    // ==================================================================

    /**
     * 输出收集器：边读边用环形缓冲保留尾部；一旦超过上限就把完整输出落盘。
     * 内存占用上界 = 环形缓冲上限 + 落盘触发前的缓冲，与命令输出总量无关。
     */
    private static final class ReadResult {
        /** 环形缓冲：保留最近的若干块 */
        private final List<byte[]> ring = new ArrayList<>();
        private long ringBytes = 0;
        /** 用于触发落盘的前缀缓冲（仅在未落盘时使用） */
        private final ByteArrayOutputStream head = new ByteArrayOutputStream();
        private OutputStream spill;
        /** 输出总量（供看门狗跨线程读取） */
        final AtomicLong totalBytes = new AtomicLong(0);
        /** 是否发生过截断 */
        final AtomicBoolean cut = new AtomicBoolean(false);

        /** 完整输出的落盘路径（未落盘时为 null） */
        volatile Path spillPath;
        /** 最终判定是否截断 */
        volatile boolean truncated;

        void pump(Process process) {
            try (InputStream in = process.getInputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    totalBytes.addAndGet(n);
                    byte[] piece = Arrays.copyOf(chunk, n);

                    ring.add(piece);
                    ringBytes += n;
                    while (ringBytes > RING_KEEP_BYTES && ring.size() > 1) {
                        ringBytes -= ring.remove(0).length;
                        cut.set(true);
                    }

                    if (spill != null) {
                        spill.write(piece);
                    } else {
                        head.write(piece, 0, n);
                        if (head.size() > MAX_OUTPUT_BYTES) {
                            spillToFile();
                            if (spill != null) {
                                head.writeTo(spill);
                                head.reset();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("读取进程输出异常", e);
            } finally {
                if (spill != null) {
                    try {
                        spill.close();
                    } catch (IOException ignored) {
                        // 关闭失败不致命
                    }
                }
            }
        }

        /** 创建落盘文件并切换写入目标；失败则保持内存模式（降级但不报错） */
        private void spillToFile() {
            try {
                Path path = Files.createTempFile("mira-bash-", ".log");
                spill = new FileOutputStream(path.toFile());
                spillPath = path;
                cut.set(true);
            } catch (IOException e) {
                log.warn("BashTool: 完整输出落盘失败，将仅在内存中保留尾部 —— {}", e.getMessage());
            }
        }

        /** 渲染尾部窗口文本 */
        String renderTail() {
            byte[] all = concatRing();
            int start = Math.max(0, all.length - MAX_OUTPUT_BYTES);
            // 避免从 UTF-8 多字节字符中间切开
            while (start < all.length && (all[start] & 0xC0) == 0x80) {
                start++;
            }
            boolean byteCut = start > 0;
            String text = decodeConsoleOutput(Arrays.copyOfRange(all, start, all.length));

            String[] lines = text.split("\n", -1);
            boolean lineCut = lines.length > MAX_OUTPUT_LINES;
            if (lineCut) {
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - MAX_OUTPUT_LINES; i < lines.length; i++) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(lines[i]);
                }
                text = sb.toString();
            }

            truncated = byteCut || lineCut || cut.get();
            if (truncated && spillPath == null) {
                // 输出总量不大但行数超限：把完整输出补写落盘，保证「完整输出」真的完整
                spillFull(all);
            }
            return text;
        }

        /** 落盘完整输出（用于行数超限但字节未超限的情形） */
        private void spillFull(byte[] all) {
            try {
                Path path = Files.createTempFile("mira-bash-", ".log");
                try (OutputStream os = Files.newOutputStream(path)) {
                    os.write(all);
                }
                spillPath = path;
            } catch (IOException e) {
                log.warn("BashTool: 完整输出落盘失败 —— {}", e.getMessage());
            }
        }

        private byte[] concatRing() {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(ringBytes, Integer.MAX_VALUE));
            for (byte[] piece : ring) {
                out.write(piece, 0, piece.length);
            }
            return out.toByteArray();
        }
    }

    /**
     * 解码子进程输出：先用 UTF-8；若出现替换字符（U+FFFD）说明不是 UTF-8，
     * 再依次尝试控制台编码（sun.stdout.encoding / sun.jnu.encoding / GBK）。
     * 这样在中国 Windows（代码页 936）下也能正确显示中文。
     */
    private static String decodeConsoleOutput(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') < 0) {
            return utf8;
        }
        for (String name : new String[]{
                System.getProperty("sun.stdout.encoding"),
                System.getProperty("sun.jnu.encoding"),
                "GBK"}) {
            if (StringUtils.isBlank(name)) {
                continue;
            }
            try {
                if (!Charset.isSupported(name)) {
                    continue;
                }
                Charset cs = Charset.forName(name);
                if (StandardCharsets.UTF_8.equals(cs)) {
                    continue;
                }
                String alt = new String(bytes, cs);
                if (alt.indexOf('\uFFFD') < 0) {
                    return alt;
                }
            } catch (Exception ignored) {
                // 尝试下一个候选字符集
            }
        }
        return utf8;
    }

    // ==================================================================
    // 安全检测
    // ==================================================================

    private String checkDangerousCommand(String command) {
        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(command).find()) {
                return "检测到可能的危险操作: " + p.pattern();
            }
        }
        return null;
    }

    /** 返回被拦截的 sleep 秒数文本，未检测到返回 null */
    private String detectBlockedSleep(String command) {
        Matcher matcher = SLEEP_PATTERN.matcher(command);
        String hit = null;
        while (matcher.find()) {
            String raw = matcher.group(1);
            try {
                if (Double.parseDouble(raw) >= 2) {
                    hit = raw;
                }
            } catch (NumberFormatException ignored) {
                // 非法数字，忽略
            }
        }
        return hit;
    }
}
