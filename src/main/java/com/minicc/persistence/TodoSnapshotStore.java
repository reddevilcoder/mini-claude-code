package com.minicc.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicc.session.TodoItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * 待办快照：只保存「当前」待办列表，每次更新整份覆盖写。
 *
 * <p>与事件日志（{@link SessionStore}，append-only）互补——日志是完整历史，快照是可直接读的最新态。
 * {@code TodoWrite} 的每次调用本就作为助手消息和工具结果留在日志里（完整历史已经有了），
 * 单独存快照只是为了让 {@code /resume} 和 UI 能「一步到位」读出当前进度，不必把整条日志读一遍、
 * 找到最后一次 TodoWrite 再解析其参数。这是「用最新态快照给事件日志做投影」的常见做法。
 *
 * <p>简化：这里不让待办随消息链持久化，而是单独存一个
 * {@code <sessionId>.todos.json}，每次整份覆盖写。
 */
public class TodoSnapshotStore {

    private final Path snapshotFile;     // <projectDir>/<sessionId>.todos.json；null 时读写为空操作
    private final ObjectMapper json;

    public TodoSnapshotStore(Path snapshotFile) {
        this.snapshotFile = snapshotFile;
        this.json = new ObjectMapper();
    }

    /** 构造一个未绑定文件的快照存储：读写都是空操作。 */
    public static TodoSnapshotStore disabled() {
        return new TodoSnapshotStore(null);
    }

    /**
     * 整份覆盖写当前待办列表（每次 TodoWrite 后调用）。
     *
     * <p>和事件日志的 append 不同，这里是 TRUNCATE_EXISTING 覆盖写——每次都把当前完整列表写成一个
     * JSON 文件、覆盖旧的。写盘失败按「尽力而为」处理：告警并继续，不拖垮主流程。
     */
    public synchronized void write(List<TodoItem> todos) {
        if (snapshotFile == null) {
            return;
        }
        try {
            Files.createDirectories(snapshotFile.getParent());
            Files.writeString(snapshotFile, json.writeValueAsString(todos),
                    CREATE, WRITE, TRUNCATE_EXISTING);           // 覆盖写，不追加
        } catch (IOException | RuntimeException e) {
            System.err.println("写入待办快照失败（已忽略）：" + e.getMessage());
        }
    }

    /** 读回当前待办列表（供 {@code /resume} 恢复进度）。文件不存在或损坏时返回空列表。 */
    public List<TodoItem> read() {
        if (snapshotFile == null || !Files.exists(snapshotFile)) {
            return List.of();
        }
        try {
            return json.readValue(Files.readString(snapshotFile), new TypeReference<List<TodoItem>>() {
            });
        } catch (Exception e) {
            System.err.println("读取待办快照失败（已忽略）：" + e.getMessage());
            return List.of();
        }
    }
}
