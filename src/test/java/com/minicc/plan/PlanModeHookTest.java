package com.minicc.plan;

import com.minicc.agent.CancelToken;
import com.minicc.permission.PermissionState;
import com.minicc.session.TodoList;
import com.minicc.session.WorkspaceState;
import com.minicc.tool.Schemas;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import com.minicc.tool.hook.PreExecuteHook;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanModeHook 的单元测试：计划态下只读放行、写操作拒绝；非计划态一律放行（交给后面的审批钩子）。
 */
class PlanModeHookTest {

    private final PlanModeHook hook = new PlanModeHook();

    @Test
    void allowsEverythingWhenNotInPlanMode() {
        PlanModeState notPlanning = new PlanModeState();   // 默认关闭

        // 非计划态：即使是写操作（Edit），这个钩子也放行——是否准写由后面的 ApprovalHook 决定。
        PreExecuteHook.Decision d = hook.beforeExecute(
                ToolCallHelper.of("1", "Edit", Map.of()), tool("Edit", false), ctx(notPlanning));

        assertTrue(d.allowed(), "非计划态下 PlanModeHook 不该拦任何工具");
    }

    @Test
    void allowsReadOnlyToolsInPlanMode() {
        PlanModeState planning = new PlanModeState();
        planning.enterPlanMode();

        PreExecuteHook.Decision read = hook.beforeExecute(
                ToolCallHelper.of("1", "Read", Map.of()), tool("Read", true), ctx(planning));
        assertTrue(read.allowed(), "计划态下只读工具应放行");
    }

    @Test
    void allowsExitPlanModeToolInPlanMode() {
        PlanModeState planning = new PlanModeState();
        planning.enterPlanMode();

        // ExitPlanMode 声明只读，必须在计划态下被放行，否则进得去出不来。
        PreExecuteHook.Decision exit = hook.beforeExecute(
                ToolCallHelper.of("1", "ExitPlanMode", Map.of()), tool("ExitPlanMode", true), ctx(planning));
        assertTrue(exit.allowed(), "计划态下 ExitPlanMode 必须放行（否则无法请求退出）");
    }

    @Test
    void deniesWriteOperationsInPlanMode() {
        PlanModeState planning = new PlanModeState();
        planning.enterPlanMode();

        for (String name : List.of("Edit", "Write", "Bash")) {
            PreExecuteHook.Decision d = hook.beforeExecute(
                    ToolCallHelper.of("1", name, Map.of()), tool(name, false), ctx(planning));
            assertFalse(d.allowed(), "计划态下 " + name + " 应被拦下");
            assertNotNull(d.reason(), "拒绝应带回原因回填给模型");
            assertTrue(d.reason().contains("Plan Mode"), "拒绝原因应说明处于 Plan Mode");
        }
    }

    private static ToolContext ctx(PlanModeState planMode) {
        return new ToolContext(Path.of("."), new WorkspaceState(), new TodoList(), false, 0,
                new CancelToken(), new PermissionState(), planMode, null, false);
    }

    private static Tool tool(String name, boolean readOnly) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> paramsSchema() { return Schemas.object(Map.of(), List.of()); }
            @Override public boolean isReadOnly() { return readOnly; }
            @Override public boolean isConcurrencySafe() { return false; }
            @Override public ToolResult execute(AssistantMessage.ToolCall call, ToolContext ctx) { return ToolResult.ok(""); }
        };
    }
}
