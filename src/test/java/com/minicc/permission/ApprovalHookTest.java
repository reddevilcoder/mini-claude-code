package com.minicc.permission;

import com.minicc.agent.CancelToken;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ApprovalHook 的单元测试：用一个假的 Approver 代替终端卡片，验证放行/拒绝/会话级放行的判定。 */
class ApprovalHookTest {

    private final PermissionState permissions = new PermissionState();

    @Test
    void readOnlyToolAllowedWithoutPrompt() {
        FakeApprover approver = new FakeApprover(Approval.reject("不该被问到"));
        ApprovalHook hook = new ApprovalHook(approver);

        PreExecuteHook.Decision d = hook.beforeExecute(
                ToolCallHelper.of("1", "Read", Map.of()), tool("Read", true), ctx());

        assertTrue(d.allowed());
        assertEquals(0, approver.calls, "只读工具不应弹卡片");
    }

    @Test
    void writeToolPromptsAndAllowsOnce() {
        FakeApprover approver = new FakeApprover(Approval.allowOnce());
        ApprovalHook hook = new ApprovalHook(approver);
        AssistantMessage.ToolCall edit = ToolCallHelper.of("1", "Edit", Map.of());

        PreExecuteHook.Decision d = hook.beforeExecute(edit, tool("Edit", false), ctx());

        assertTrue(d.allowed());
        assertEquals(1, approver.calls);
        assertFalse(permissions.acceptEdits(), "只放行本次，不应进入自动接受编辑");
    }

    @Test
    void allowSessionGrantsAndSkipsNextPrompt() {
        FakeApprover approver = new FakeApprover(Approval.allowSession());
        ApprovalHook hook = new ApprovalHook(approver);
        AssistantMessage.ToolCall edit = ToolCallHelper.of("1", "Edit", Map.of());

        assertTrue(hook.beforeExecute(edit, tool("Edit", false), ctx()).allowed());
        assertTrue(permissions.acceptEdits(), "会话级放行后应进入自动接受编辑");

        // 第二次同类操作不应再弹卡片。
        assertTrue(hook.beforeExecute(edit, tool("Edit", false), ctx()).allowed());
        assertEquals(1, approver.calls, "会话级放行后同类操作应直接放行，不再弹卡片");
    }

    @Test
    void rejectReturnsDenyWithReason() {
        FakeApprover approver = new FakeApprover(Approval.reject("先别动这个文件"));
        ApprovalHook hook = new ApprovalHook(approver);

        PreExecuteHook.Decision d = hook.beforeExecute(
                ToolCallHelper.of("1", "Write", Map.of()), tool("Write", false), ctx());

        assertFalse(d.allowed());
        assertEquals("先别动这个文件", d.reason());
    }

    @Test
    void memoryDirWriteAutoAllowedWithoutPrompt() {
        FakeApprover approver = new FakeApprover(Approval.reject("不该被问到"));
        ApprovalHook hook = new ApprovalHook(approver);
        Path memoryDir = Path.of("/home/u/.mcc/projects/proj/memory");
        AssistantMessage.ToolCall write = ToolCallHelper.of("1", "Write",
                Map.of("file_path", memoryDir.resolve("feedback_testing.md").toString()));

        PreExecuteHook.Decision d = hook.beforeExecute(write, tool("Write", false), ctxWithMemory(memoryDir, false));

        assertTrue(d.allowed(), "写入记忆目录应自动放行");
        assertEquals(0, approver.calls, "记忆写入不该弹审批卡片");
    }

    @Test
    void nonMemoryWriteStillPromptsEvenWithMemoryDir() {
        FakeApprover approver = new FakeApprover(Approval.allowOnce());
        ApprovalHook hook = new ApprovalHook(approver);
        Path memoryDir = Path.of("/home/u/.mcc/projects/proj/memory");
        AssistantMessage.ToolCall write = ToolCallHelper.of("1", "Write",
                Map.of("file_path", "/home/u/work/proj/src/App.java"));

        PreExecuteHook.Decision d = hook.beforeExecute(write, tool("Write", false), ctxWithMemory(memoryDir, false));

        assertTrue(d.allowed());
        assertEquals(1, approver.calls, "记忆目录之外的写入照旧弹卡片");
    }

    @Test
    void extractionRoundDeniesWritesOutsideMemoryDir() {
        FakeApprover approver = new FakeApprover(Approval.allowOnce());
        ApprovalHook hook = new ApprovalHook(approver);
        Path memoryDir = Path.of("/home/u/.mcc/projects/proj/memory");
        AssistantMessage.ToolCall write = ToolCallHelper.of("1", "Write",
                Map.of("file_path", "/home/u/work/proj/src/App.java"));

        // memoryWritesOnly=true：提取回合只能写记忆目录，写到别处一律拒绝（不弹卡片）。
        PreExecuteHook.Decision d = hook.beforeExecute(write, tool("Write", false), ctxWithMemory(memoryDir, true));

        assertFalse(d.allowed(), "提取回合写到记忆目录之外应被拒绝");
        assertEquals(0, approver.calls, "提取无人值守，拒绝时不弹卡片");
    }

    private ToolContext ctx() {
        return new ToolContext(Path.of("."), new WorkspaceState(), new TodoList(), false, 0,
                new CancelToken(), permissions);
    }

    private ToolContext ctxWithMemory(Path memoryDir, boolean memoryWritesOnly) {
        return new ToolContext(Path.of("."), new WorkspaceState(), new TodoList(), false, 0,
                new CancelToken(), permissions, memoryDir, memoryWritesOnly);
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

    /** 记录调用次数、返回预设选择的假 Approver。 */
    private static class FakeApprover implements Approver {
        private final Approval scripted;
        int calls = 0;

        FakeApprover(Approval scripted) {
            this.scripted = scripted;
        }

        @Override
        public Approval requestApproval(AssistantMessage.ToolCall call, Tool tool, ToolContext ctx) {
            calls++;
            return scripted;
        }
    }
}
