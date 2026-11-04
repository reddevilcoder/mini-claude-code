package com.minicc.permission;

import com.minicc.tool.Schemas;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PermissionState 的单元测试：会话级放行的两类——文件修改开关、Bash 命令前缀。 */
class PermissionStateTest {

    private static final Tool EDIT = fakeTool("Edit");
    private static final Tool WRITE = fakeTool("Write");
    private static final Tool BASH = fakeTool("Bash");
    private static final Tool READ = fakeTool("Read");

    @Test
    void fileEditNotGrantedUntilSessionAllowed() {
        PermissionState state = new PermissionState();
        AssistantMessage.ToolCall edit = ToolCallHelper.of("1", "Edit", Map.of());
        assertFalse(state.isGranted(edit, EDIT), "初始状态下文件修改应需要审批");

        state.grantSession(edit, EDIT);
        assertTrue(state.acceptEdits(), "放行后应进入自动接受编辑");
        assertTrue(state.isGranted(edit, EDIT), "之后的 Edit 应直接放行");
        // 同一会话里 Write 也应被同一开关覆盖。
        assertTrue(state.isGranted(ToolCallHelper.of("2", "Write", Map.of()), WRITE));
    }

    @Test
    void bashGrantedByCommandPrefixOnly() {
        PermissionState state = new PermissionState();
        AssistantMessage.ToolCall mvnTest = ToolCallHelper.of("1", "Bash", Map.of("command", "mvn -q test"));
        AssistantMessage.ToolCall mvnPkg = ToolCallHelper.of("2", "Bash", Map.of("command", "mvn package"));
        AssistantMessage.ToolCall git = ToolCallHelper.of("3", "Bash", Map.of("command", "git status"));

        assertFalse(state.isGranted(mvnTest, BASH));
        state.grantSession(mvnTest, BASH);

        // 放行 mvn 前缀后，其它 mvn 命令免审批，但 git 仍需审批。
        assertTrue(state.isGranted(mvnPkg, BASH), "同前缀的 mvn 命令应免审批");
        assertFalse(state.isGranted(git, BASH), "不同前缀的 git 命令仍应审批");
    }

    @Test
    void bashPrefixIsFirstWord() {
        PermissionState state = new PermissionState();
        assertEquals("mvn", state.bashPrefix(ToolCallHelper.of("1", "Bash", Map.of("command", "mvn -q test"))));
        assertEquals("git", state.bashPrefix(ToolCallHelper.of("2", "Bash", Map.of("command", "git"))));
    }

    @Test
    void readOnlyToolsAreNeverGranted() {
        // 只读工具不走放行逻辑（isGranted 对非写工具返回 false，审批钩子会在 isReadOnly 分支提前放行）。
        PermissionState state = new PermissionState();
        assertFalse(state.isGranted(ToolCallHelper.of("1", "Read", Map.of()), READ));
    }

    private static Tool fakeTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> paramsSchema() { return Schemas.object(Map.of(), List.of()); }
            @Override public boolean isReadOnly() { return "Read".equals(name); }
            @Override public boolean isConcurrencySafe() { return false; }
            @Override public ToolResult execute(AssistantMessage.ToolCall call, ToolContext ctx) { return ToolResult.ok(""); }
        };
    }
}
