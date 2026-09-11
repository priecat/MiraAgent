package net.itzq.mira.modules.toolfun.code;

import lombok.extern.slf4j.Slf4j;
import net.itzq.mira.modules.ai.agent.AgentContextHolder;
import net.itzq.mira.modules.ai.client.tool.annotation.Tool;
import net.itzq.mira.modules.ai.client.tool.annotation.ToolParam;
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
 * - 设置 CLAUDECODE=1 环境变量标记
 *
 * @author tangzq
 */
@Slf4j
public class BashTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300; // 5 分钟
    private static final int MAX_OUTPUT_LINES = 500;

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
            String workDir = System.getProperty("user.dir");

            log.info("bash WorkDir:"+workDir);

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
            if (isWindows) {
                // 修复: 不再使用 chcp 65001 >nul &&，避免 cmd.exe /c 长命令解析挂起
                cmdArray = new String[]{"cmd.exe", "/c", command};
            } else {
                cmdArray = new String[]{"sh", "-c", command};
            }

            ProcessBuilder pb = new ProcessBuilder(cmdArray);
            pb.directory(new java.io.File(workDir));
            pb.environment().put("GIT_EDITOR", "true");
            pb.environment().put("CLAUDECODE", "1");


            pb.redirectErrorStream(true);

            Charset outputCharset = StandardCharsets.UTF_8;

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            boolean[] truncated = {false};

            // 修复: 将读取逻辑放入单独的线程，避免 readLine 阻塞导致主线程无法进行超时控制
            Thread readerThread = new Thread(() -> {
                int lineCount = 0;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), outputCharset))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (lineCount >= MAX_OUTPUT_LINES) {
                            truncated[0] = true;
                            // 继续消耗输出防止底层缓冲区满导致进程死锁
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
            readerThread.setDaemon(true); // 设置为守护线程，防止JVM卡死
            readerThread.start();

            // 主线程负责等待超时
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);

            // 等待读取线程结束（此时进程已死，流会关闭，读取很快结束）
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

    /**
     * 检测危险命令，返回警告信息（不阻止执行）
     */
    private String checkDangerousCommand(String command) {
        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(command).find()) {
                return "检测到可能的危险操作: " + p.pattern();
            }
        }
        return null;
    }

    /**
     * 检测阻塞 sleep 模式（>2s 的 sleep 命令应使用专用工具）
     */
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
