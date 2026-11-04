package com.minicc.tool.builtin;

import com.minicc.agent.CancelToken;
import com.minicc.permission.PermissionState;
import com.minicc.plan.PlanApprover;
import com.minicc.plan.PlanModeState;
import com.minicc.session.TodoList;
import com.minicc.session.WorkspaceState;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ExitPlanModeTool 的单元测试：用假 PlanApprover 代替弹框，验证接受/继续完善/不在计划态三条路径。 */
class ExitPlanModeToolTest {

    @Test
    void exitsPlanModeWhenUserAccepts() {
        PlanModeState state = new PlanModeState();
        state.enterPlanMode();
        ExitPlanModeTool tool = new ExitPlanModeTool(new FakePlanApprover(new PlanApprover.Result(true, "")));

        ToolResult result = tool.execute(call(), ctx(state));

        assertFalse(result.error());
        assertFalse(state.isPlanMode(), "选「接受并执行」后应退出计划态");
        assertTrue(result.content().contains("已退出 Plan Mode"));
    }

    @Test
    void staysInPlanModeWhenUserWantsToRefine() {
        PlanModeState state = new PlanModeState();
        state.enterPlanMode();
        ExitPlanModeTool tool = new ExitPlanModeTool(
                new FakePlanApprover(new PlanApprover.Result(false, "再补一步回滚方案")));

        ToolResult result = tool.execute(call(), ctx(state));

        assertFalse(result.error());
        assertTrue(state.isPlanMode(), "选「继续完善」后应仍处于计划态");
        assertTrue(result.content().contains("继续完善"));
        assertTrue(result.content().contains("再补一步回滚方案"), "用户反馈应回填给模型");
    }

    @Test
    void returnsErrorWhenNotInPlanMode() {
        PlanModeState state = new PlanModeState();   // 非计划态
        ExitPlanModeTool tool = new ExitPlanModeTool(new FakePlanApprover(new PlanApprover.Result(true, "")));

        ToolResult result = tool.execute(call(), ctx(state));

        assertTrue(result.error(), "不在计划态时调用应返回错误观察");
    }

    private static AssistantMessage.ToolCall call() {
        return ToolCallHelper.of("1", ExitPlanModeTool.NAME, Map.of());
    }

    private static ToolContext ctx(PlanModeState planMode) {
        return new ToolContext(Path.of("."), new WorkspaceState(), new TodoList(), false, 0,
                new CancelToken(), new PermissionState(), planMode, null, false);
    }

    /** 返回预设退出结果的假 PlanApprover。 */
    private static class FakePlanApprover implements PlanApprover {
        private final Result exitResult;

        FakePlanApprover(Result exitResult) {
            this.exitResult = exitResult;
        }

        @Override public boolean confirmEnterPlan() {
            return false;
        }

        @Override public Result confirmPlan() {
            return exitResult;
        }
    }
}
