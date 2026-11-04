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
 * EnterPlanMode 工具（M8）：模型在非计划态下、判断当前是「非简单的实现任务」、想先规划再动手时调用它，
 * 主动提议进入 Plan Mode。是否进入由用户拍板——工具通过 {@link PlanApprover} 弹两选项确认框请用户批准。
 *
 * <p>这是进入计划态的第二条路径（第一条是用户的 {@code /plan} 命令）。两者的区别只在「谁发起、要不要弹框」：
 * {@code /plan} 由用户自己发起、无需再问；{@code EnterPlanMode} 由模型发起，必须经用户在弹框里拍板。
 *
 * <p>它和 {@link ExitPlanModeTool} <b>对称</b>——一个负责「提议进入」、一个负责「请求退出」，都靠
 * 确认框 + 回填观察驱动循环，所以主循环一行都不用改。只读（不碰文件/环境，无需审批），不可并行
 * （要暂停循环等用户选）。它只在<b>非计划态</b>下有意义——已经在计划态里再调它会返回「当前已在 Plan Mode」
 * 的错误观察，让模型纠正。
 *
 * <p>mini-cc 只保留「弹框请用户批准进入计划态」这一条主干，
 * 简化掉 interview 分阶段、plan 文件等进阶能力。
 */
public class EnterPlanModeTool implements Tool {

    public static final String NAME = "EnterPlanMode";

    private final PlanApprover planApprover; // 弹进入确认框、等用户选（CLI 实现，底层共用 CardPrompter）

    public EnterPlanModeTool(PlanApprover planApprover) {
        this.planApprover = planApprover;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        // 讲清「何时该用 / 何时不该用」，引导模型恰当地主动提议。
        return """
                当你即将开始一个**非简单的实现任务**、且先让用户确认思路能避免返工时，主动调用本工具进入 Plan Mode。
                适合用它的情况（满足任一即可）：需求有多种合理实现方案、改动会影响既有行为或结构、
                涉及架构取舍、预计要动多个文件、或需求尚不清晰需要先调研。
                不要用它的情况：改一两行的小修复、按明确指示即可完成的任务、纯调研/检索类任务。
                拿不准时，倾向于先规划——提前对齐比返工划算。
                本工具不接收参数；是否进入由用户在弹框里确认。""";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return Schemas.object(new LinkedHashMap<>(), List.of());   // 无参数
    }

    @Override
    public boolean isReadOnly() {
        return true;   // 不碰文件/环境，无需审批；计划态下也会被 PlanModeHook 放行
    }

    @Override
    public boolean isConcurrencySafe() {
        return false;  // 要暂停循环、与用户交互，不能并行
    }

    @Override
    public ToolResult execute(AssistantMessage.ToolCall call, ToolContext ctx) {
        // 已在计划态里再调它没有意义——返回错误观察让模型纠正（通常是它误判了状态）。
        if (ctx.planMode().isPlanMode()) {
            return ToolResult.error("当前已在 Plan Mode，无需再次进入。");
        }
        boolean enter = planApprover.confirmEnterPlan();   // 弹两选项框、等用户选
        if (enter) {
            ctx.planMode().enterPlanMode();                // 用户批准 → 置位计划态，下一轮 PlanModeStage 即生效
            return ToolResult.ok("""
                    已进入 Plan Mode：接下来请只用只读工具（Read/Grep/Glob）彻底调研代码库、理解现有模式、
                    设计一份具体的实现计划，期间不要修改任何文件或执行有副作用的命令；
                    计划好了用 ExitPlanMode 请求用户批准后再执行。""");
        }
        // 用户选「直接实现」：不进入计划态，回填观察让模型照常动手。
        return ToolResult.ok("用户选择直接开始实现，未进入 Plan Mode。");
    }
}
