package com.minicc.cli.command;

import com.minicc.cli.Renderer;
import com.minicc.session.Session;

/**
 * {@code /plan}（M8）：进入 Plan Mode——此后到退出前，harness 拦住所有写操作，Agent 只能调研与规划。
 *
 * <p>这是进入计划态的<b>第一条路径</b>（第二条是模型主动调用的 {@code EnterPlanMode} 工具）。两者的区别只在
 * 「谁发起、要不要弹框」：{@code /plan} 由用户直接发起、<b>无需弹框</b>——用户自己拿定主意要先规划，harness
 * 没有理由再问一次；模型发起的 {@code EnterPlanMode} 则必须经用户在弹框里拍板。
 *
 * <p>它只做一件事：把当前会话切进计划态（置位 {@code session.planMode()}）。下一轮组装上下文时
 * {@code PlanModeStage} 自然生效，{@code PlanModeHook} 开始拦住写操作。本命令从 M1 起就作为占位列出、
 * 标注「后续版本提供」，M8 把占位升级为真正进入计划态。
 */
public final class PlanCommand implements SlashCommand {

    private final Session session;
    private final Renderer renderer;

    public PlanCommand(Session session, Renderer renderer) {
        this.session = session;
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return "plan";
    }

    @Override
    public String description() {
        return "进入 Plan Mode：只调研与规划，不做任何修改";
    }

    @Override
    public boolean run(String args) {
        session.planMode().enterPlanMode();  // 置位计划态；下一轮 PlanModeStage/PlanModeHook 即生效
        renderer.planModeEntered();          // 打印「⏸ 已进入 Plan Mode……」
        return false;                        // 不退出程序，仍停在 > 等下一条输入（需求）
    }
}
