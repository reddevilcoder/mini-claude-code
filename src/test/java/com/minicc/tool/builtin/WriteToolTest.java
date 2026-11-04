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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WriteTool 的单元测试。 */
class WriteToolTest {

    private final WriteTool tool = new WriteTool();

    private ToolResult write(Path dir, WorkspaceState ws, String path, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file_path", path);
        m.put("content", content);
        return tool.execute(ToolCallHelper.of("1", "Write", m),
                new ToolContext(dir, ws, new TodoList(), false, 120000L,
                        new CancelToken(), new PermissionState()));
    }

    @Test
    void createsNewFileWithoutPriorRead(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("sub/New.java"); // 父目录不存在，应自动创建
        WorkspaceState ws = new WorkspaceState();
        ToolResult r = write(dir, ws, f.toString(), "class New {}\n");
        assertFalse(r.error());
        assertTrue(r.content().contains("已新建"));
        assertEquals("class New {}\n", Files.readString(f));
    }

    @Test
    void overwriteRequiresPriorRead(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("Exist.java");
        Files.writeString(f, "old\n");
        WorkspaceState ws = new WorkspaceState(); // 没读过
        ToolResult r = write(dir, ws, f.toString(), "new\n");
        assertTrue(r.error());
        assertTrue(r.content().contains("先用 Read"));
        assertEquals("old\n", Files.readString(f)); // 未被覆盖
    }

    @Test
    void overwriteAfterRead(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("Exist.java");
        Files.writeString(f, "old\n");
        WorkspaceState ws = new WorkspaceState();
        ws.markRead(f);
        ToolResult r = write(dir, ws, f.toString(), "new\n");
        assertFalse(r.error());
        assertTrue(r.content().contains("已覆盖"));
        assertEquals("new\n", Files.readString(f));
    }
}
