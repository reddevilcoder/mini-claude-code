package com.minicc.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 会话文件路径推导：把项目路径清洗成安全目录名，拼出会话日志与待办快照的路径。
 *
 * <p>路径组织方式：
 * 按「一个项目一个目录、一次会话一个文件」组织——同一台机器上不同项目的会话互不混淆，
 * 且目录名在各平台都合法。
 */
public final class SessionPaths {

    private static final String LOG_SUFFIX = ".jsonl";
    private static final String TODO_SUFFIX = ".todos.json";

    private final Path stateDir;   // 默认 ~/.mcc，可由配置 stateDir 覆盖

    public SessionPaths(Path stateDir) {
        this.stateDir = stateDir;
    }

    /** 本项目的会话目录：{@code <stateDir>/projects/<清洗后的项目路径>/}。 */
    public Path projectDir(Path projectRoot) {
        String safe = sanitize(projectRoot.toAbsolutePath().normalize().toString());
        return stateDir.resolve("projects").resolve(safe);
    }

    /** 某次会话的日志文件：{@code <projectDir>/<sessionId>.jsonl}。 */
    public Path sessionLog(Path projectRoot, String sessionId) {
        return projectDir(projectRoot).resolve(sessionId + LOG_SUFFIX);
    }

    /** 某次会话的待办快照文件：{@code <projectDir>/<sessionId>.todos.json}。 */
    public Path todoSnapshot(Path projectRoot, String sessionId) {
        return projectDir(projectRoot).resolve(sessionId + TODO_SUFFIX);
    }

    /**
     * 本项目的记忆目录：{@code <projectDir>/memory/}（M5 的 auto memory 都放这里）。
     *
     * <p>复用既有的 {@link #projectDir} 与 {@code sanitize}，只在它下面再拼一层 {@code memory/}，
     * 和「一个项目一个目录」的组织方式保持一致。
     */
    public Path memoryDir(Path projectRoot) {
        return projectDir(projectRoot).resolve("memory");
    }

    /**
     * 本项目的工具结果落盘目录：{@code <projectDir>/tool-results/}（M6 的超大工具结果都落这里）。
     *
     * <p>复用既有的 {@link #projectDir} 与 {@code sanitize}，和 {@link #memoryDir} 同一套路——
     * 在"一个项目一个目录"的组织方式下再拼一层 {@code tool-results/}。
     *
     * <p>简化：更完整的实现会把 tool-results 目录按会话再分一层（{@code projectDir/sessionId/tool-results}）；
     * mini-cc 直接挂在项目目录下，同项目多次会话共享（文件名是全局唯一的 callId，不会撞）。
     */
    public Path toolResultsDir(Path projectRoot) {
        return projectDir(projectRoot).resolve("tool-results");
    }

    /**
     * 本项目最近一次会话的 sessionId（供 {@code /resume} 不带参数时恢复最近一次）。
     *
     * <p>「默认恢复最近一次」：列出项目目录下的
     * {@code .jsonl}，按文件修改时间取最新的一个，取其文件名（去掉 .jsonl 后缀）作为 sessionId。
     */
    public Optional<String> latestSessionId(Path projectRoot) {
        Path dir = projectDir(projectRoot);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(LOG_SUFFIX))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return name.substring(0, name.length() - LOG_SUFFIX.length());
                    });
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * 把任意路径清洗成安全目录名：非字母数字一律转 {@code '-'}。
     *
     * <p>例如 {@code /Users/wangzheng/work/orders} → {@code -Users-wangzheng-work-orders}。
     *
     * <p>TODO（简化）：更健壮的做法会对超长路径截断并追加哈希后缀以避开文件名长度上限；
     * 教学项目的项目路径一般不会超长，mini-cc 先不做这层防护。
     */
    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "-");
    }
}
