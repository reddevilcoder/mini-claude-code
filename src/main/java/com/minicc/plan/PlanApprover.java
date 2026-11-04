package com.minicc.plan;

/**
 * 计划确认交互抽象（M8）：弹出确认卡片、暂停循环、等用户选编号。只做「计划相关的确认」这一件事。
 *
 * <p>由 CLI 的 {@code CliPlanApprover} 实现，底层交给 {@code CardPrompter} 渲染（和审批/提问卡片
 * 共用一套「标题 + 编号选项 + 读一行」组件）。它对应 Plan Mode「两条进入、一条退出」里需要弹框的两处：
 * <ul>
 *   <li>{@link #confirmEnterPlan()}：{@code EnterPlanMode} 的「要不要进入」（两选项）；</li>
 *   <li>{@link #confirmPlan()}：{@code ExitPlanMode} 的「要不要按此计划开始」（两选项，选「继续完善」时
 *       还收集一句意见回填给模型）。</li>
 * </ul>
 *
 * <p>（用户的 {@code /plan} 命令由用户自己发起、无需弹框，所以不经过本抽象。）
 * 两处确认都是「两选项」，故返回值都收敛成布尔——进入直接返回 {@code boolean}，退出返回一个
 * {@link Result}（多带一句「继续完善」时的反馈文字），不再单独建只有两个取值的枚举。
 */
public interface PlanApprover {

    /**
     * {@code EnterPlanMode} 弹「是否进入 Plan Mode？」两选项框。
     *
     * @return {@code true}=进入计划态；{@code false}=不进入、直接开始实现。
     *         （卡片被 Ctrl-C 取消时同样返回 {@code false}，但会另行置位取消信号，取消整个任务。）
     */
    boolean confirmEnterPlan();

    /**
     * {@code ExitPlanMode} 弹「是否按此计划开始？」两选项框。
     *
     * @return 用户的选择 +（选「继续完善」时）填写的反馈文字。
     */
    Result confirmPlan();

    /**
     * 退出确认的结果。
     *
     * @param canExecute {@code true}=接受计划、退出计划态开始执行；{@code false}=继续完善计划（仍留在计划态）。
     * @param feedback   选「继续完善」时用户填写的反馈文字（可为空）；选「接受」时为空。
     */
    record Result(boolean canExecute, String feedback) {
    }
}
