package com.minicc.plan;

/**
 * 计划态开关（M8）：独立于 {@code PermissionState} 的会话级状态，只回答一个问题——「现在是不是计划态」。
 *
 * <p>有些实现会把计划态和 {@code acceptEdits}/{@code default} 并列成一种权限模式；mini-cc 简化为一个
 * <b>独立的布尔开关类</b>，和 {@code acceptEdits} 彻底解耦——因为去掉「退出即自动接受编辑」这个选项后，
 * 计划态的进入/退出已经完全不牵动 {@code acceptEdits}，各自成类反而最清爽：{@code PermissionState} 专管
 * 「会话级放行」，{@code PlanModeState} 专管「现在是不是计划态」。
 *
 * <p>它的归属与传递方式和既有会话级状态完全一致：一个会话一个实例，由 {@code Session} 持有
 * （{@code /clear} 调用 Session.clear 时替换为新开关），经 {@code ToolContext} 下发给 {@code PlanModeHook} 与
 * {@code PlanModeStage} 查询。进入计划态有两条路（用户 {@code /plan}、模型 {@code EnterPlanMode}），
 * 退出只有一条（模型 {@code ExitPlanMode}），它们最终都落到这个开关上。
 *
 * <p><b>面试关注：这是一个最小状态机。</b>状态只有普通执行与计划态两种，进入/退出重复赋同一布尔值
 * 是幂等的；是否应该弹确认框、重复进入是否报错属于上层工具的交互规则。volatile 保证可见性，
 * 不使“读取状态—等待用户选择—切换状态”成为原子事务，当前依赖交互工具串行执行来简化并发控制。
 */
public class PlanModeState {

    // 计划态：true 表示只调研、不写（volatile：工具可能在并行线程里查询它）。
    private volatile boolean planMode = false;

    /** 进入计划态（{@code /plan} 命令或 {@code EnterPlanMode} 被用户批准时置位）。 */
    public void enterPlanMode() {
        planMode = true;
    }

    /** 退出计划态（{@code ExitPlanMode} 被用户批准时复位）。只关计划态，不碰 {@code acceptEdits}。 */
    public void exitPlanMode() {
        planMode = false;
    }

    /** 当前是否处于计划态（{@code PlanModeHook} 判断能不能写、{@code PlanModeStage} 判断要不要注入提示）。 */
    public boolean isPlanMode() {
        return planMode;
    }
}
