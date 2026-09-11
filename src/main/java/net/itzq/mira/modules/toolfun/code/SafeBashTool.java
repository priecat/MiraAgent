package net.itzq.mira.modules.toolfun.code;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.tool.annotation.Tool;
import net.itzq.mira.modules.ai.tool.annotation.ToolParam;
import net.itzq.mira.modules.config.GlobalConfigManager;
import net.itzq.mira.modules.toolfun.ToolFun;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BashTool - Shell 命令执行与安全管控
 *
 * - 作为"最后手段"——优先使用专用工具而非 Bash
 * - Git 安全协议：禁止 --force、--no-verify、reset --hard 等危险操作
 * - 危险命令检测并警告（rm -rf、git reset --hard 等）
 * - 默认超时 5 分钟
 * - 每个命令启动新 shell 进程（非持久会话）
 * - 支持降权执行（通过 SANDBOX_USER 环境变量配置）
 *
 * @author tangzq
 */
@Slf4j
public class SafeBashTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300; // 5 分钟
    private static final int MAX_OUTPUT_LINES = 500;

    // 读取环境变量中的沙箱用户名。如果设置了，Bash命令将以该用户身份执行。
    private static final String SANDBOX_USER = GlobalConfigManager.config().getAgentConfig().getBashSandBoxUser();

    /** 危险命令模式（检测并警告） */
    private static final Pattern[] DANGEROUS_PATTERNS = {
            Pattern.compile("rm\\s+-rf\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("rm\\s+-r\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+reset\\s+--hard", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+push\\s+--force", Pattern.CASE_INSENSITIVE),
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

    /** sleep 阻塞模式检测（>2s 的 sleep） */
    private static final Pattern SLEEP_BLOCK_PATTERN =
            Pattern.compile("^sleep\\s+(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

    @Tool(name = ToolFun.TOOL_Bash,
          display = "执行命令",
          description = "执行 Shell 命令。\n\n"
                  + "重要: 避免使用此工具运行 find、grep、cat、head、tail、sed、awk 或 echo 命令，"
                  + "除非在验证了专用工具无法完成任务后明确指示。请使用对应的专用工具：\n"
                  + "  - 文件搜索: 使用 Glob（而非 find 或 ls）\n"
                  + "  - 内容搜索: 使用 Grep（而非 grep 或 rg）\n"
                  + "  - 读取文件: 使用 Read（而非 cat/head/tail）\n"
                  + "  - 编辑文件: 使用 Edit（而非 sed/awk）\n"
                  + "  - 写入文件: 使用 Write（而非 echo >/cat <<EOF）\n\n"
                  + "Git 安全协议:\n"
                  + "- 永远不要更新 git config\n"
                  + "- 除非用户明确要求，永远不要执行破坏性 git 命令"
                  + "（push --force、reset --hard、checkout . 等）\n"
                  + "- 不要使用带 -i 标志的 git 命令（交互式）\n"
                  + "- 除非明确要求，不要跳过 hooks（--no-verify、--no-gpg-sign）\n"
                  + "- 避免不必要的 sleep 命令\n"
    )
    public String bash(
            @ToolParam(description = "要执行的 Shell 命令（必填）") String command,
            @ToolParam(description = "超时秒数，默认 300（5 分钟）", required = false) Integer timeout,
            AgentContextHolder contextHolder) {

        try {
            String workDir = GlobalConfigManager.config().getAgentConfig().getBashWorkspceDir();

            log.info("bash WorkDir: " + workDir);

            int timeoutSec = timeout != null ? Math.min(timeout, 1800) : DEFAULT_TIMEOUT_SECONDS;

            // 检查是否应使用专用工具
            for (String preferred : PREFER_DEDICATED_TOOL) {
                if (command.trim().startsWith(preferred + " ") || command.trim().equals(preferred)) {
                    log.warn("BashTool: 检测到 {} 命令，应优先使用对应的专用工具", preferred);
                }
            }

            // 危险命令检测
            String warning = checkDangerousCommand(command);
            if (warning != null) {
                log.warn("BashTool: 检测到危险命令模式 —— {}", warning);
            }

            // sleep 阻塞检测
            String sleepWarning = detectBlockedSleep(command);
            if (sleepWarning != null) {
                return String.format(
                        "已阻止: %s。运行阻塞命令请使用 run_in_background: true 参数。"
                                + "流式事件监控请使用 Monitor 工具。如确需延迟（限速/节流），请控制在 2 秒以内。",
                        sleepWarning);
            }

            // Windows 适配
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            String[] cmdArray;
            ProcessBuilder pb;

            if (isWindows) {
                cmdArray = new String[]{"cmd.exe", "/c", command};
                pb = new ProcessBuilder(cmdArray);
                pb.directory(new java.io.File(workDir));
            } else {
                if (StringUtils.isNotBlank(SANDBOX_USER)) {
                    // === 降权执行模式 ===
                    log.info("BashTool: 启用降权执行，目标用户: {}", SANDBOX_USER);

                    // 1. 包装环境变量（防止 su 重置环境）
                    // 2. 包装工作目录切换（防止 agent_user 无权访问原工作目录报错）
                    // 注意：这里使用 su -s /bin/sh 强制指定shell，防止 agent_user 被设置为 /sbin/nologin
                    String escapedWorkDir = workDir.replace("\"", "\\\"");
                    String wrappedCommand = String.format(
                            "export GIT_EDITOR=true; cd \"%s\" 2>/dev/null || cd /tmp; %s",
                            escapedWorkDir, command
                    );

                    cmdArray = new String[]{"su", "-s", "/bin/sh", SANDBOX_USER, "-c", wrappedCommand};
                    pb = new ProcessBuilder(cmdArray);

                    // 注意：降权模式下不能使用 pb.directory(workDir)。
                    // 因为如果 agent_user 没有该目录的读取/执行权限，ProcessBuilder 在启动进程时会直接抛出 IOException。
                    // 所以我们将其设为根目录，实际的 cd 已在 wrappedCommand 中处理。
                    pb.directory(new java.io.File("/"));
                } else {
                    // === 普通执行模式 ===
                    cmdArray = new String[]{"sh", "-c", command};
                    pb = new ProcessBuilder(cmdArray);
                    pb.directory(new java.io.File(workDir));
                }
            }

            // 设置环境变量（对于普通模式有效，降权模式已在命令内 export）
            pb.environment().put("GIT_EDITOR", "true");
            pb.redirectErrorStream(true);

            Charset outputCharset = StandardCharsets.UTF_8;
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            boolean[] truncated = {false};

            Thread readerThread = new Thread(() -> {
                int lineCount = 0;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), outputCharset))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (lineCount >= MAX_OUTPUT_LINES) {
                            truncated[0] = true;
                            while (reader.readLine() != null) { /* drain */ }
                            break;
                        }
                        output.append(line).append("\n");
                        lineCount++;
                    }
                } catch (Exception e) {
                    log.error("读取进程输出异常", e);
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            readerThread.join(2000);

            if (!finished) {
                process.destroyForcibly();
                return String.format("命令执行超时 (%d 秒)，已强制终止:\n%s\n\n%s",
                        timeoutSec, command,
                        output.length() > 0 ? output.toString() : "(无输出)");
            }

            int exitCode = process.exitValue();

            StringBuilder result = new StringBuilder();
            result.append(String.format("工作目录: %s\n命令: %s\n退出码: %d\n\n",
                    workDir, command, exitCode));

            if (warning != null) {
                result.append(String.format("⚠️ 警告: %s\n\n", warning));
            }

            result.append(output);

            if (truncated[0]) {
                result.append(String.format("\n[输出已截断: 仅显示前 %d 行]", MAX_OUTPUT_LINES));
            }

            return result.toString();

        } catch (Exception e) {
            log.error("BashTool 执行失败", e);
            return "命令执行失败: " + e.getMessage();
        }
    }

    private String checkDangerousCommand(String command) {
        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(command).find()) {
                return "检测到可能的危险操作: " + p.pattern();
            }
        }
        return null;
    }

    private String detectBlockedSleep(String command) {
        Matcher m = SLEEP_BLOCK_PATTERN.matcher(command.trim());
        if (m.find()) {
            int secs = Integer.parseInt(m.group(1));
            if (secs >= 2) {
                return "standalone sleep " + secs + "s";
            }
        }
        return null;
    }
}
