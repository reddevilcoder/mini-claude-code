package com.minicc.tool.builtin;

import com.minicc.agent.CancelToken;
import com.minicc.permission.PermissionState;
import com.minicc.session.TodoList;
import com.minicc.session.WorkspaceState;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BashTool 的单元测试。沙箱关闭（sandbox=false）以免依赖 sandbox-exec/bwrap。
 */
class BashToolTest {

    private final BashTool tool = new BashTool();

    private ToolResult run(Path dir, Map<String, Object> args) {
        return tool.execute(ToolCallHelper.of("1", "Bash", args),
                new ToolContext(dir, new WorkspaceState(), new TodoList(), false, 120000L,
                        new CancelToken(), new PermissionState()));
    }

    private static Map<String, Object> cmd(String command, Integer timeout) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("command", command);
        if (timeout != null) {
            m.put("timeout", timeout);
        }
        return m;
    }

    @Test
    void capturesOutputAndExitCode(@TempDir Path dir) {
        ToolResult r = run(dir, cmd("echo hello", null));
        assertFalse(r.error());
        assertTrue(r.content().contains("hello"));
        assertTrue(r.content().contains("exit code: 0"));
    }

    @Test
    void nonZeroExitIsError(@TempDir Path dir) {
        ToolResult r = run(dir, cmd("exit 3", null));
        assertTrue(r.error());
        assertTrue(r.content().contains("exit code: 3"));
    }

    @Test
    void dangerousCommandBlocked(@TempDir Path dir) {
        ToolResult r = run(dir, cmd("rm -rf /", null));
        assertTrue(r.error());
        assertTrue(r.content().contains("安全护栏"));
    }

    @Test
    void timeoutIsAborted(@TempDir Path dir) {
        ToolResult r = run(dir, cmd("sleep 5", 300)); // 300ms 超时
        assertTrue(r.error());
        assertTrue(r.content().contains("超时"));
    }

    @Test
    void runsInWorkingDirectory(@TempDir Path dir) {
        ToolResult r = run(dir, cmd("pwd", null));
        assertFalse(r.error());
        // macOS 下 /tmp 可能是 /private/tmp 的符号链接，用 endsWith 容错。
        assertTrue(r.content().contains(dir.getFileName().toString()));
    }
}
