package com.minicc.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * stdio 传输：子进程的 stdin/stdout 上逐行收发 JSON（MCP 规范的 stdio transport）。
 *
 * <p>子进程 stderr 追加写入日志文件，不污染终端；读线程是守护线程，进程退出时自然结束。
 *
 * <p>注意：MCP Server 是本机任意可执行程序，<b>不在 mcc 的 Bash 沙箱内运行</b>，
 * 拥有与当前用户相同的权限——只配置你信任的 Server。
 */
public final class StdioTransport implements Transport {

    private final List<String> command;
    private final Map<String, String> env;
    private final Path workingDir;
    private final Path stderrLog;
    private Process process;
    private OutputStream stdin;

    public StdioTransport(List<String> command, Map<String, String> env, Path workingDir, Path stderrLog) {
        this.command = List.copyOf(command);
        this.env = Map.copyOf(env);
        this.workingDir = workingDir;
        this.stderrLog = stderrLog;
    }

    @Override
    public synchronized void start(Consumer<String> onMessage, Runnable onClose) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command));
        pb.environment().putAll(env);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        if (stderrLog != null) {
            Files.createDirectories(stderrLog.getParent());
            pb.redirectError(ProcessBuilder.Redirect.appendTo(stderrLog.toFile()));
        } else {
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        process = pb.start();
        stdin = process.getOutputStream();
        Process p = process;
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) {
                        onMessage.accept(line);
                    }
                }
            } catch (IOException ignore) {
                // 进程被关闭。
            } finally {
                onClose.run();
            }
        }, "mcp-stdio-" + command.get(0));
        reader.setDaemon(true);
        reader.start();
    }

    @Override
    public synchronized void send(String json) throws IOException {
        if (stdin == null) {
            throw new IOException("transport 未启动");
        }
        stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    @Override
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    @Override
    public synchronized void close() {
        if (process == null) {
            return;
        }
        try {
            stdin.close();   // 规范：先关 stdin，给 Server 优雅退出的机会
        } catch (IOException ignore) {
            // ignore
        }
        try {
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
