package com.minicc.plan;

import com.minicc.tool.Tool;
import com.minicc.tool.ToolContext;
import com.minicc.tool.hook.PreExecuteHook;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * 计划态拦截钩子（M8）：计划态下只放行只读工具，拦住一切写操作（Edit/Write/Bash）。
 *
 * <p>挂在 M1 预留、M3 用上的 {@code HookChain} 上，排在 {@code ApprovalHook} <b>之前</b>——计划态下
 * 写操作先在这里被拦掉，{@code HookChain} 短路，根本走不到审批；非计划态下本钩子一律放行，交给后面的
 * {@code ApprovalHook} 按老规矩判断。于是 M2 的写操作工具与 M3 的审批钩子一行都不用改。
 *
 * <p><b>两个钩子分工要记牢</b>：{@code PlanModeHook} 回答「<b>现在能不能写</b>」（计划态下一律不能），
 * {@code ApprovalHook} 回答「<b>这次写准不准</b>」（要不要弹审批卡片）。两者在链上一前一后、职责不同、
 * 读的状态都不重叠（本钩子查 {@code planMode}、审批钩子查 {@code permissions}）。
 */
public class PlanModeHook implements PreExecuteHook {

    @Override
    public Decision beforeExecute(AssistantMessage.ToolCall call, Tool tool, ToolContext ctx) {
        // 非计划态：这个钩子不管事，直接放行交给后面的审批钩子。
        if (!ctx.planMode().isPlanMode()) {
            return Decision.allow();
        }
        // 计划态：只读工具（Read/Grep/Glob/TodoWrite/AskUserQuestion/EnterPlanMode/ExitPlanMode 等）放行。
        //   ExitPlanMode 声明只读，故能在这里被放行——否则模型在计划态里连「请求退出」都做不到，
        //   就成了进得去出不来的陷阱。
        if (tool.isReadOnly()) {
            return Decision.allow();
        }
        // 计划态：写操作（Edit/Write/Bash）一律拦下，把原因回填给模型，让它回到「只调研」。
        return Decision.deny("当前处于 Plan Mode：只能调研（只读工具），不能修改文件或执行命令。"
                + "请先完成调研并产出计划，再用 ExitPlanMode 请求批准后执行。");
    }
}
