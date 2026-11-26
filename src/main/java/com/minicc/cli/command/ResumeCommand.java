package com.minicc.cli.command;

import com.minicc.cli.Renderer;
import com.minicc.persistence.SessionLoader;
import com.minicc.persistence.SessionPaths;
import com.minicc.persistence.SessionStore;
import com.minicc.persistence.TodoSnapshotStore;
import com.minicc.session.Session;

import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code /resume}：把上次（或指定）会话的日志读回来、重建内存 Session 与待办，接着上次的进度继续。
 *
 * <p>行为（取其主干）：
 * <ul>
 *   <li>{@code /resume}（不带参数）：恢复<b>本项目最近一次</b>会话（按日志文件修改时间取最新的一个）；</li>
 *   <li>{@code /resume <sessionId>}：恢复指定会话；</li>
 *   <li>恢复成功后，后续新消息<b>继续追加到被恢复的同一个 .jsonl</b>（续旧账本，不新建文件）。</li>
 * </ul>
 *
 * <p>与 {@code /clear} 相反：{@code /clear} 开新账本（新 sessionId/新文件），{@code /resume} 续旧账本。
 *
 * <p>错误处理：找不到可恢复会话、日志为空/损坏到无可回放，都给一句友好提示、
 * 停留在当前会话，不报错退出。
 */
public final class ResumeCommand implements SlashCommand {

    private final Session session;
    private final SessionPaths paths;
    private final Renderer renderer;
    private final SessionLoader loader = new SessionLoader();

    public ResumeCommand(Session session, SessionPaths paths, Renderer renderer) {
        this.session = session;
        this.paths = paths;
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return "resume";
    }

    @Override
    public String description() {
        return "恢复上次会话，接着上次的进度继续（/resume 或 /resume <sessionId>）";
    }

    @Override
    public boolean run(String args) {
        Path projectRoot = session.workingDir();
        Optional<String> sessionId = (args == null || args.isBlank())
                ? paths.latestSessionId(projectRoot)   // 不带参数：最近一次
                : Optional.of(args.strip());           // 指定 sessionId
        if (sessionId.isEmpty()) {
            renderer.info("没有可恢复的会话。");
            return false;
        }

        String id = sessionId.get();
        SessionStore store = new SessionStore(paths.sessionLog(projectRoot, id));
        TodoSnapshotStore todoStore = new TodoSnapshotStore(paths.todoSnapshot(projectRoot, id));

        SessionLoader.Result result = loader.restore(session, id, store, todoStore,
                new com.minicc.checkpoint.CheckpointStore(paths.checkpointDir(projectRoot, id)));   // M10
        if (result.messageCount() == 0) {
            renderer.info("没有可恢复的会话。");
            return false;
        }
        renderer.resumed(result.messageCount(), result.todos());
        return false;
    }
}
