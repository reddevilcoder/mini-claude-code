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

/** EditTool 的单元测试。 */
class EditToolTest {

    private final EditTool tool = new EditTool();

    private ToolResult edit(Path dir, WorkspaceState ws, Map<String, Object> args) {
        return tool.execute(ToolCallHelper.of("1", "Edit", args),
                new ToolContext(dir, ws, new TodoList(), false, 120000L,
                        new CancelToken(), new PermissionState()));
    }

    private static Map<String, Object> args(String path, String oldStr, String newStr, Boolean all) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file_path", path);
        m.put("old_string", oldStr);
        m.put("new_string", newStr);
        if (all != null) {
            m.put("replace_all", all);
        }
        return m;
    }

    @Test
    void failsWhenNotReadFirst(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("A.java");
        Files.writeString(f, "int a = 1;\n");
        WorkspaceState ws = new WorkspaceState(); // 没登记读过
        ToolResult r = edit(dir, ws, args(f.toString(), "int a = 1;", "int a = 2;", null));
        assertTrue(r.error());
        assertTrue(r.content().contains("先用 Read"));
    }

    @Test
    void editsAfterRead(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("A.java");
        Files.writeString(f, "int a = 1;\n");
        WorkspaceState ws = new WorkspaceState();
        ws.markRead(f);
        ToolResult r = edit(dir, ws, args(f.toString(), "int a = 1;", "int a = 2;", null));
        assertFalse(r.error());
        assertEquals("int a = 2;\n", Files.readString(f));
    }

    @Test
    void failsWhenNotUnique(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("A.java");
        Files.writeString(f, "x\nx\n");
        WorkspaceState ws = new WorkspaceState();
        ws.markRead(f);
        ToolResult r = edit(dir, ws, args(f.toString(), "x", "y", null));
        assertTrue(r.error());
        assertTrue(r.content().contains("不唯一"));
    }

    @Test
    void replaceAllReplacesEveryOccurrence(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("A.java");
        Files.writeString(f, "x\nx\n");
        WorkspaceState ws = new WorkspaceState();
        ws.markRead(f);
        ToolResult r = edit(dir, ws, args(f.toString(), "x", "y", true));
        assertFalse(r.error());
        assertEquals("y\ny\n", Files.readString(f));
    }

    @Test
    void failsWhenNotFound(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("A.java");
        Files.writeString(f, "int a = 1;\n");
        WorkspaceState ws = new WorkspaceState();
        ws.markRead(f);
        ToolResult r = edit(dir, ws, args(f.toString(), "not-there", "y", null));
        assertTrue(r.error());
        assertTrue(r.content().contains("未在文件中找到"));
    }
}
