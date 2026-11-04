package com.minicc.tool.bash;

import com.minicc.agent.CancelToken;
import com.minicc.tool.ToolResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 命令执行器：用子进程执行一条命令，捕获输出与退出码，支持超时中止。
 *
 * <p>流程：先过 {@link CommandGuard} 粗筛危险命令 → 按是否沙箱决定 argv（{@link Sandbox}）→
 * 以项目目录为工作目录启动子进程、合并捕获 stdout/stderr → 超时则强制中止 → 输出过长则截断。
 */
public class CommandRunner {

    /** 输出最大保留字符数，超出则截断（M4 的"结果落盘预算"会做得更细，这里先简单截断）。 */
    private static final int MAX_OUTPUT_CHARS = 30_000;

    /** 等待子进程时的轮询间隔（毫秒）：既用于超时判断，也用于查询取消信号。 */
    private static final long POLL_MS = 100;

    /**
     * 执行命令。
     *
     * @param command     shell 命令
     * @param workingDir  工作目录（= 会话项目目录）
     * @param timeoutMs   超时（毫秒）
     * @param sandbox     是否包进 OS 沙箱
     * @param cancelToken 协作式取消信号（M3）：Ctrl-C 置位后中止子进程树。可为 null（不支持取消）
     */
    public ToolResult run(String command, Path workingDir, long timeoutMs, boolean sandbox, CancelToken cancelToken) {
        if (command == null || command.isBlank()) {
            return ToolResult.error("缺少必填参数 command。");
        }
        // 第一层：危险命令护栏（非安全边界，仅防呆）。
        if (CommandGuard.isDangerous(command)) {
            return ToolResult.error("该命令被安全护栏拦截，拒绝执行：" + command);
        }

        List<String> argv = sandbox ? Sandbox.wrap(command, workingDir) : Sandbox.plain(command);
        ProcessBuilder pb = new ProcessBuilder(argv)
                .directory(workingDir.toFile())
                .redirectErrorStream(true); // stderr 合并进 stdout

        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            return ToolResult.error("启动命令失败：" + e.getMessage());
        }

        // 用单独线程读输出，避免输出缓冲区写满导致子进程阻塞。
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> readInto(p, out));
        reader.setDaemon(true);
        reader.start();

        try {
            // 等待子进程时同时响应"超时"与"取消"：短间隔轮询，既是唯一可能长时间运行的工具，
            // 也是 Ctrl-C 取消要中止的对象（取消信号贯穿整个工具执行）。
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!p.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                if (cancelToken != null && cancelToken.isCancelled()) {
                    p.destroyForcibly();          // 取消：中止子进程树（复用超时同一路径）
                    reader.join(1000);
                    return ToolResult.error("命令已被用户中断（Ctrl-C）：" + command
                            + "\n" + truncate(snapshot(out)));
                }
                if (System.currentTimeMillis() >= deadline) {
                    p.destroyForcibly();
                    reader.join(1000);
                    return ToolResult.error("命令超时（" + timeoutMs + "ms）已中止：" + command
                            + "\n" + truncate(snapshot(out)));
                }
            }
            reader.join(2000);
            int code = p.exitValue();
            String body = truncate(snapshot(out));
            String tail = "\n[exit code: " + code + "]";
            // 退出码非 0 的命令作为"错误观察"回传，让模型据此修正（把工具错误当观察）。
            return code == 0 ? ToolResult.ok(body + tail) : ToolResult.error(body + tail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return ToolResult.error("命令被中断：" + command);
        }
    }

    /** 把子进程输出读进 StringBuilder。 */
    private static void readInto(Process p, StringBuilder sink) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) != -1) {
                synchronized (sink) {
                    sink.append(buf, 0, n);
                    if (sink.length() > MAX_OUTPUT_CHARS + 1000) {
                        // 已经远超上限，没必要再读了。
                        break;
                    }
                }
            }
        } catch (IOException ignore) {
            // 进程结束后流关闭会抛异常，忽略即可。
        }
    }

    /** 与写线程同步地取输出快照。 */
    private static String snapshot(StringBuilder sink) {
        synchronized (sink) {
            return sink.toString();
        }
    }

    /** 超长输出截断并加标记。调用前读线程已 join，传入的是输出快照。 */
    private static String truncate(String body) {
        if (body.length() <= MAX_OUTPUT_CHARS) {
            return body.stripTrailing();
        }
        return body.substring(0, MAX_OUTPUT_CHARS).stripTrailing()
                + "\n… [输出过长，已截断，共约 " + body.length() + " 字符]";
    }
}
