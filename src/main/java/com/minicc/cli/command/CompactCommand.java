package com.minicc.cli.command;

import com.minicc.cli.Renderer;
import com.minicc.compact.CompactionManager;
import com.minicc.session.Session;

/**
 * {@code /compact}：手动触发一次上下文压缩（M6 实现）。
 *
 * <p>和每轮自动投影的区别是——
 * 它<b>不看阈值</b>，强制把 Snip → 微压缩 → 折叠 → 自动压缩都跑一遍，把结果<b>提交进</b>
 * {@link CompactionManager} 的状态（让后续轮次直接复用这次的摘要），并打印"刻意可见"的分层过程与
 * 压缩前后的 Token 变化。真正的编排在 {@link CompactionManager#compactNow(Session)} 里。
 */
public final class CompactCommand implements SlashCommand {

    private final Renderer renderer;
    private final Session session;
    private final CompactionManager compactionManager;

    public CompactCommand(Renderer renderer, Session session, CompactionManager compactionManager) {
        this.renderer = renderer;
        this.session = session;
        this.compactionManager = compactionManager;
    }

    @Override
    public String name() {
        return "compact";
    }

    @Override
    public String description() {
        return "手动触发一次上下文压缩（折叠 + 自动压缩）";
    }

    @Override
    public boolean run(String args) {
        if (session.history().isEmpty()) {
            renderer.info("当前会话历史为空，无需压缩。");
            return false;
        }
        compactionManager.compactNow(session);
        return false;
    }
}
