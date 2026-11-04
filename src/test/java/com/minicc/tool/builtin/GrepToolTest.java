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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GrepTool 的单元测试（无论底层用 rg 还是 Java 降级实现，结果都应正确）。 */
class GrepToolTest {

    private final GrepTool tool = new GrepTool();

    private ToolResult grep(Path dir, Map<String, Object> args) {
        return tool.execute(ToolCallHelper.of("1", "Grep", args),
                new ToolContext(dir, new WorkspaceState(), new TodoList(), false, 120000L,
                        new CancelToken(), new PermissionState()));
    }

    private void seed(Path dir) throws IOException {
        Files.writeString(dir.resolve("Foo.java"), "class Foo {\n  void validate() {}\n}\n");
        Files.writeString(dir.resolve("Bar.java"), "class Bar {\n  void run() {}\n}\n");
        Files.writeString(dir.resolve("notes.txt"), "validate this text\n");
    }

    @Test
    void contentModeFindsMatches(@TempDir Path dir) throws IOException {
        seed(dir);
        ToolResult r = grep(dir, Map.of("pattern", "validate", "output_mode", "content"));
        assertFalse(r.error());
        assertTrue(r.content().contains("validate"));
    }

    @Test
    void filesWithMatchesListsFiles(@TempDir Path dir) throws IOException {
        seed(dir);
        ToolResult r = grep(dir, Map.of("pattern", "validate", "output_mode", "files_with_matches"));
        assertFalse(r.error());
        assertTrue(r.content().contains("Foo.java"));
        assertTrue(r.content().contains("notes.txt"));
        assertFalse(r.content().contains("Bar.java"), "Bar.java 不含 validate，不应出现");
    }

    @Test
    void globFilterLimitsScope(@TempDir Path dir) throws IOException {
        seed(dir);
        ToolResult r = grep(dir, Map.of(
                "pattern", "validate",
                "glob", "*.java",
                "output_mode", "files_with_matches"));
        assertFalse(r.error());
        assertTrue(r.content().contains("Foo.java"));
        assertFalse(r.content().contains("notes.txt"), "glob 限定 *.java，txt 不应出现");
    }

    @Test
    void reportsNoMatches(@TempDir Path dir) throws IOException {
        seed(dir);
        ToolResult r = grep(dir, Map.of("pattern", "zzz_not_present", "output_mode", "content"));
        assertFalse(r.error());
        assertTrue(r.content().contains("没有匹配"));
    }
}
