package com.minicc.cli.command;

import com.minicc.cli.Renderer;
import com.minicc.persistence.SessionPaths;
import com.minicc.persistence.SessionStore;
import com.minicc.persistence.TodoSnapshotStore;
import com.minicc.session.Session;

import java.nio.file.Path;
import java.util.UUID;

/**
 * {@code /clear}：清空内存中的当前会话，并切到一个新的 sessionId 与新日志文件（开新账本）。
 *
 * <p>旧日志仍留在磁盘，之后仍可 {@code /resume}。与 {@link ResumeCommand} 相反：
 * {@code /clear} 开新账本，{@code /resume} 续旧账本。
 */
public final class ClearCommand implements SlashCommand {

    private final Session session;
    private final SessionPaths paths;
    private final Renderer renderer;

    public ClearCommand(Session session, SessionPaths paths, Renderer renderer) {
        this.session = session;
        this.paths = paths;
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description() {
        return "清空当前会话，重新开始";
    }

    @Override
    public boolean run(String args) {
        session.clear();
        rotateSession();
        renderer.info("会话已清空。");
        return false;
    }

    /**
     * 切到一个新的 sessionId 与新日志/快照文件。
     * 旧日志留在磁盘、仍可 {@code /resume}；新消息从此写入新文件。
     */
    private void rotateSession() {
        String id = UUID.randomUUID().toString();
        Path root = session.workingDir();
        SessionStore store = new SessionStore(paths.sessionLog(root, id));
        TodoSnapshotStore todoStore = new TodoSnapshotStore(paths.todoSnapshot(root, id));
        session.attachPersistence(id, store, todoStore,
                new com.minicc.checkpoint.CheckpointStore(paths.checkpointDir(root, id)));   // M10
    }
}
