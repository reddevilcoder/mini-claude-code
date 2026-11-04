package com.minicc.tool.builtin;

import com.minicc.plan.PlanApprover;
import com.minicc.tool.Schemas;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ExitPlanMode 工具（M8）：模型在计划态下、计划准备好、要请求用户批准时调用它。
 *
 * <p>它不接收计划内容作参数——计划已经作为普通消息呈现在上下文里（计划留在终端与上下文、
 * 不写磁盘文件）。它只负责「请求批准」：通过 {@link PlanApprover} 弹两选项卡片，按用户选择切换计划态、
 * 回填一条观察驱动循环继续。
 *
 * <p>把退出做成<b>工具</b>（而不是像 {@code /plan} 进入那样做成用户命令）有两个好处：一是由模型决定
 * 「计划好了」的时机（它可能在调研中途产出若干过程性文字，那些都不是最终计划）；二是退出的结果天然作为
 * 观察回流，循环无需任何特殊控制流就能继续执行——选「接受」时计划态已关、写操作重新放行，模型在下一轮
 * 自然开始执行计划第一步（执行时每次编辑仍走 {@code ApprovalHook} 逐个弹卡）；选「继续完善」时仍留在
 * 计划态，模型据反馈继续调研、再次调用本工具。这也是 M8 的 {@code AgentLoop} 一行都不用改的原因。
 *
 * <p>它和 {@link EnterPlanModeTool} <b>对称</b>——都是模型调用、靠确认框 + 回填观察驱动循环。
 * 只读（不碰文件/环境，故无需审批、计划态下也被 {@code PlanModeHook} 放行），不可并行（要暂停循环等用户选）。
 */
public class ExitPlanModeTool implements Tool {

    public static final String NAME = "ExitPlanMode";

    private final PlanApprover planApprover; // 弹退出确认框、等用户选（CLI 实现，底层共用 CardPrompter）

    public ExitPlanModeTool(PlanApprover planApprover) {
        this.planApprover = planApprover;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        // 讲清何时该用、何时不该用。
        return """
                当你正处于 Plan Mode、已经把实现计划写好、准备让用户审阅批准时调用本工具。
                - 只在"需要规划一个要写代码的实现任务"时用；纯调研/检索/读代码理解的任务**不要**用它。
                - 不要用 AskUserQuestion 去问"这个计划行不行/要不要开始"——那正是本工具要做的事。
                - 调用前请确保计划完整、无歧义；若对需求或方案还有疑问，先用 AskUserQuestion 澄清。
                本工具不接收计划内容作参数：计划应当已经作为普通消息呈现给用户。""";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return Schemas.object(new LinkedHashMap<>(), List.of());   // 无参数
    }

    @Override
    public boolean isReadOnly() {
        return true;   // 不碰文件/环境，无需审批；计划态下也会被 PlanModeHook 放行（否则进得去出不来）
    }

    @Override
    public boolean isConcurrencySafe() {
        return false;  // 要暂停循环、与用户交互，不能并行
    }

    @Override
    public ToolResult execute(AssistantMessage.ToolCall call, ToolContext ctx) {
        // 不在计划态却调它没有意义——返回错误观察让模型纠正（通常是它误判了状态）。
        if (!ctx.planMode().isPlanMode()) {
            return ToolResult.error("当前不在 Plan Mode，无需退出。");
        }
        PlanApprover.Result result = planApprover.confirmPlan();   // 弹卡片、等用户选
        if (result.canExecute()) {
            ctx.planMode().exitPlanMode();                         // 只退出计划态，不碰 acceptEdits
            return ToolResult.ok("计划已批准。已退出 Plan Mode，开始执行；每次编辑仍会弹审批卡片确认。");
        }
        // 继续完善计划：仍留在计划态，把用户反馈作为观察回填，驱动模型给出新版计划。
        String feedback = result.feedback() == null ? "" : result.feedback().strip();
        return ToolResult.ok("用户暂不执行，希望继续完善计划（仍处于 Plan Mode）。"
                + (feedback.isEmpty() ? "" : "用户反馈：" + feedback));
    }
}
