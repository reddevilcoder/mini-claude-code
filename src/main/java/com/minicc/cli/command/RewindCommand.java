package com.minicc.cli.command;

import com.minicc.checkpoint.CheckpointStore;
import com.minicc.cli.CardPrompter;
import com.minicc.cli.Renderer;
import com.minicc.session.Session;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /rewind}：回到某一轮用户输入之前（M10）。
 *
 * <p>三种模式：文件+对话 / 仅对话 / 仅文件。
 * 对话回退走 {@link Session#rewindConversation}（以 JSONL 为准、追加 REWIND 事件）；
 * 文件回退走 {@link CheckpointStore#restoreFrom}（最早备份还原 + 最晚哈希检测冲突）。
 *
 * <p>面试关注：为什么用 turnId 而不是消息下标——压缩会重写内存历史，下标失效；
 * 为什么提示 Bash——检查点只覆盖 Edit/Write，Shell 的副作用无法撤销，必须如实告知。
 */
public final class RewindCommand implements SlashCommand {

    private static final int MAX_TURNS = 10;
    private static final int PROMPT_PREVIEW = 60;

    private final Session session;
    private final CardPrompter prompter;
    private final Renderer renderer;

    public RewindCommand(Session session, CardPrompter prompter, Renderer renderer) {
        this.session = session;
        this.prompter = prompter;
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return "rewind";
    }

    @Override
    public String description() {
        return "回退到某一轮之前（文件 / 对话）";
    }

    @Override
    public boolean run(String args) {
        List<Session.Turn> all = session.turns();
        if (all.isEmpty()) {
            renderer.info("没有可回退的轮次。");
            return false;
        }
        List<Session.Turn> turns = all.subList(Math.max(0, all.size() - MAX_TURNS), all.size());
        List<String> options = new ArrayList<>();
        for (Session.Turn t : turns) {
            String mark = session.checkpoints().hasChanges(t.turnId()) ? " [改过文件]" : "";
            options.add(truncate(t.prompt()) + mark);
        }
        int pick = prompter.select(null, "回退到哪一轮之前？", options);
        if (pick < 0) {
            return false;
        }
        Session.Turn target = turns.get(pick);

        int mode = prompter.select(null, "回退内容",
                List.of("文件 + 对话", "仅对话", "仅文件"));
        if (mode < 0) {
            return false;
        }
        boolean files = mode == 0 || mode == 2;
        boolean conversation = mode == 0 || mode == 1;

        if (files && !rewindFiles(target.turnId())) {
            return false;
        }
        if (conversation) {
            String prompt = session.rewindConversation(target.turnId());
            renderer.info("对话已回退。该轮原始输入：" + (prompt == null ? "(无)" : prompt));
        }
        return false;
    }

    /** @return false 表示用户放弃 */
    private boolean rewindFiles(String turnId) {
        CheckpointStore cp = session.checkpoints();
        if (!cp.enabled() || cp.indexOf(turnId) < 0) {
            renderer.info("该轮之后没有文件检查点，跳过文件回退。");
            return true;
        }
        try {
            CheckpointStore.Manifest summary = cp.summarizeFrom(turnId);
            List<String> preview = new ArrayList<>();
            summary.files.values().forEach(e ->
                    preview.add((e.existed ? "还原 " : "删除 ") + session.workingDir().relativize(Path.of(e.path))));
            if (!summary.bashCommands.isEmpty()) {
                preview.add("⚠ 这些轮次执行过 Bash，其副作用无法撤销：");
                summary.bashCommands.stream().limit(5).forEach(c -> preview.add("   $ " + c));
            }
            if (preview.isEmpty()) {
                preview.add("（没有文件改动）");
            }
            if (prompter.select(preview, "确认回退文件？", List.of("确认", "取消")) != 0) {
                return false;
            }
            CheckpointStore.RestoreReport report = cp.restoreFrom(turnId, false);
            if (!report.conflicts().isEmpty()) {
                List<String> lines = new ArrayList<>();
                report.conflicts().forEach(p -> lines.add("冲突（回退后被外部修改）：" + p));
                int choice = prompter.select(lines, "如何处理冲突文件？", List.of("跳过冲突文件", "强制覆盖"));
                if (choice == 1) {
                    report = cp.restoreFrom(turnId, true);
                }
            }
            report.restored().forEach(session.workspace()::forget);
            report.deleted().forEach(session.workspace()::forget);
            cp.dropFrom(turnId);
            renderer.info("文件已回退：还原 " + report.restored().size() + "，删除 " + report.deleted().size()
                    + (report.conflicts().isEmpty() ? "" : "，冲突 " + report.conflicts().size()));
            return true;
        } catch (Exception e) {
            renderer.error("文件回退失败：" + e.getMessage());
            return false;
        }
    }

    private static String truncate(String s) {
        String one = s == null ? "" : s.replace('\n', ' ');
        return one.length() <= PROMPT_PREVIEW ? one : one.substring(0, PROMPT_PREVIEW) + "…";
    }
}
