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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EnterPlanModeTool 的单元测试：用假 PlanApprover 代替弹框，验证进入/不进入/已在计划态三条路径。 */
class EnterPlanModeToolTest {

    @Test
    void entersPlanModeWhenUserConfirms() {
        PlanModeState state = new PlanModeState();
        EnterPlanModeTool tool = new EnterPlanModeTool(new FakePlanApprover(true, null));

        ToolResult result = tool.execute(call(), ctx(state));

        assertFalse(result.error());
        assertTrue(state.isPlanMode(), "用户选「进入」后应置位计划态");
        assertTrue(result.content().contains("已进入 Plan Mode"));
    }

    @Test
    void staysOutWhenUserDeclines() {
        PlanModeState state = new PlanModeState();
        EnterPlanModeTool tool = new EnterPlanModeTool(new FakePlanApprover(false, null));

        ToolResult result = tool.execute(call(), ctx(state));

        assertFalse(result.error());
        assertFalse(state.isPlanMode(), "用户选「直接实现」后不应进入计划态");
        assertTrue(result.content().contains("直接开始实现"));
    }

    @Test
    void returnsErrorWhenAlreadyInPlanMode() {
        PlanModeState state = new PlanModeState();
        state.enterPlanMode();
        FakePlanApprover approver = new FakePlanApprover(true, null);
        EnterPlanModeTool tool = new EnterPlanModeTool(approver);

        ToolResult result = tool.execute(call(), ctx(state));

        assertTrue(result.error(), "已在计划态时再调应返回错误观察");
        assertEquals(0, approver.enterCalls, "已在计划态时不该再弹框");
    }

    private static AssistantMessage.ToolCall call() {
        return ToolCallHelper.of("1", EnterPlanModeTool.NAME, Map.of());
    }

    private static ToolContext ctx(PlanModeState planMode) {
        return new ToolContext(Path.of("."), new WorkspaceState(), new TodoList(), false, 0,
                new CancelToken(), new PermissionState(), planMode, null, false);
    }

    /** 记录调用次数、返回预设选择的假 PlanApprover。 */
    private static class FakePlanApprover implements PlanApprover {
        private final boolean enterChoice;
        private final Result exitResult;
        int enterCalls = 0;

        FakePlanApprover(boolean enterChoice, Result exitResult) {
            this.enterChoice = enterChoice;
            this.exitResult = exitResult;
        }

        @Override public boolean confirmEnterPlan() {
            enterCalls++;
            return enterChoice;
        }

        @Override public Result confirmPlan() {
            return exitResult;
        }
    }
}
