package com.minicc.cli;

import com.minicc.agent.CancelToken;
import com.minicc.ask.Answer;
import com.minicc.ask.Question;
import com.minicc.ask.QuestionAsker;

import java.util.ArrayList;
import java.util.List;

/**
 * 提问交互的 CLI 实现（M3）：把 {@link QuestionAsker} 的抽象落到终端卡片上。
 *
 * <p>职责：把模型发起的每个 {@link Question} 组装成选择卡片（并<b>总是自动补一个"其他"项</b>让用户自由输入），
 * 调用 {@link CardPrompter} 打印、读一行编号，再把选择翻译回 {@link Answer}。（审批是另一件事，见
 * {@link CliApprover}；两者只共用 {@link CardPrompter}。）
 *
 * <p>卡片在等编号时按 Ctrl-C，{@link CardPrompter} 返回"取消"。本类据此置位 {@link CancelToken}
 * 取消整个任务，并把已作答的问题返回（工具会把空回答当成"用户未作答"处理）。
 */
public class CliQuestionAsker implements QuestionAsker {

    private final CardPrompter prompter;
    private final CancelToken cancelToken;

    public CliQuestionAsker(CardPrompter prompter, CancelToken cancelToken) {
        this.prompter = prompter;
        this.cancelToken = cancelToken;
    }

    @Override
    public List<Answer> askQuestions(List<Question> questions) {
        List<Answer> answers = new ArrayList<>();
        for (Question q : questions) {
            // 组装选项标签，并"总是自动补充一个'其他'项"（由系统补 Other）。
            List<String> labels = new ArrayList<>();
            for (Question.Option o : q.options()) {
                String desc = (o.description() == null || o.description().isBlank())
                        ? "" : "  —  " + o.description();
                labels.add(o.label() + desc);
            }
            int otherIndex = labels.size();
            labels.add("其他（我来补充）");

            List<String> preview = List.of(q.question());
            String title = "选择：" + q.header();

            if (q.multiSelect()) {
                List<Integer> chosen = prompter.multiSelect(preview, title, labels);
                if (chosen == null) {      // Ctrl-C：取消任务
                    cancelToken.cancel();
                    return answers;
                }
                List<String> picks = new ArrayList<>();
                for (int i : chosen) {
                    if (i == otherIndex) {
                        String other = prompter.readLine("  补充说明> ");
                        if (other != null && !other.isBlank()) {
                            picks.add(other.strip());
                        }
                    } else {
                        picks.add(q.options().get(i).label());
                    }
                }
                answers.add(new Answer(q.header(), q.question(), picks));
            } else {
                int idx = prompter.select(preview, title, labels);
                if (idx < 0) {             // Ctrl-C：取消任务
                    cancelToken.cancel();
                    return answers;
                }
                String pick;
                if (idx == otherIndex) {
                    String other = prompter.readLine("  补充说明> ");
                    pick = (other == null || other.isBlank()) ? "（未补充）" : other.strip();
                } else {
                    pick = q.options().get(idx).label();
                }
                answers.add(new Answer(q.header(), q.question(), List.of(pick)));
            }
        }
        return answers;
    }
}
