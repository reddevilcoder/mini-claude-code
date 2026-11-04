package com.minicc.cli;

import com.minicc.agent.CancelToken;
import com.minicc.plan.PlanApprover;

import java.util.List;

/**
 * 计划确认的 CLI 实现（M8）：把 {@link PlanApprover} 的抽象落到终端卡片上。
 *
 * <p>职责：组装 {@code EnterPlanMode}（进入）与 {@code ExitPlanMode}（退出）两张「两选项」确认卡片，
 * 调用 {@link CardPrompter} 打印并读一行编号，再把用户的选择翻译回结果。两张卡片都复用和审批/提问卡片
 * 同一套 {@link CardPrompter}（「标题 + 编号选项 + 读一行」）。
 *
 * <p>卡片在等编号时按 Ctrl-C，{@link CardPrompter} 返回「取消」。本类据此置位 {@link CancelToken}
 * 取消整个任务——取消不等于任何一个选项：{@code EnterPlanMode} 被取消时<b>不进入</b>计划态、也<b>不等于</b>
 * 选「直接实现」；{@code ExitPlanMode} 被取消时计划态保持原样。和审批/提问卡片的行为完全一致。
 */
public class CliPlanApprover implements PlanApprover {

    private final CardPrompter prompter;
    private final CancelToken cancelToken;

    public CliPlanApprover(CardPrompter prompter, CancelToken cancelToken) {
        this.prompter = prompter;
        this.cancelToken = cancelToken;
    }

    /** 进入确认：弹「是否进入 Plan Mode？」两选项框。选 1=进入，选 2=直接实现，Ctrl-C=取消任务（不进入）。 */
    @Override
    public boolean confirmEnterPlan() {
        List<String> preview = List.of(
                "要先进入 Plan Mode 吗？我想先调研代码库、设计实现方案，交你审阅后再动手。");
        List<String> options = List.of(
                "是，进入 Plan Mode（先出计划后改动）",
                "否，直接开始实现");
        int idx = prompter.select(preview, "EnterPlanMode", options);
        if (idx < 0) {              // Ctrl-C / Ctrl-D：取消整个任务（不进入、也不等于选「直接实现」）
            cancelToken.cancel();
            return false;
        }
        return idx == 0;            // 0=进入；1=直接实现
    }

    /**
     * 退出确认：弹「是否按此计划开始？」两选项框。
     * 选 1=接受并执行；选 2=继续完善（再读一行反馈意见，可留空）；Ctrl-C=取消任务（计划态保持原样）。
     */
    @Override
    public Result confirmPlan() {
        List<String> options = List.of(
                "接受计划并执行",
                "不执行，继续完善计划");
        int idx = prompter.select(null, "ExitPlanMode：是否按此计划开始？", options);
        if (idx < 0) {              // Ctrl-C / Ctrl-D：取消整个任务
            cancelToken.cancel();
            return new Result(false, "");
        }
        if (idx == 0) {             // 接受计划并执行 → 退出计划态
            return new Result(true, "");
        }
        // 继续完善计划：再读一行用户意见（可留空），随结果回填给模型。
        String feedback = prompter.readLine("  想让我怎么改进计划？（直接回车可跳过）> ");
        if (feedback == null) {     // 读反馈时 Ctrl-C：也按取消任务处理
            cancelToken.cancel();
            return new Result(false, "");
        }
        return new Result(false, feedback.strip());
    }
}
