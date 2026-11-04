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

/** ReadTool 的单元测试。 */
class ReadToolTest {

    private final ReadTool tool = new ReadTool();

    private ToolResult read(Path dir, Map<String, Object> args) {
        return tool.execute(ToolCallHelper.of("1", "Read", args),
                new ToolContext(dir, new WorkspaceState(), new TodoList(), false, 120000L,
                        new CancelToken(), new PermissionState()));
    }

    @Test
    void readsFileWithLineNumbers(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "hello\nworld\n");

        ToolResult r = read(dir, Map.of("file_path", file.toString()));

        assertFalse(r.error());
        // cat -n 风格：行号 + 制表符 + 内容。
        assertTrue(r.content().contains("\thello"), "应包含带行号的 hello");
        assertTrue(r.content().contains("\tworld"), "应包含带行号的 world");
        assertTrue(r.content().contains("1\thello"), "第一行行号应为 1");
    }

    @Test
    void honorsOffsetAndLimit(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("b.txt");
        Files.writeString(file, "l1\nl2\nl3\nl4\nl5\n");

        ToolResult r = read(dir, Map.of(
                "file_path", file.toString(),
                "offset", 2,
                "limit", 2));

        assertFalse(r.error());
        assertTrue(r.content().contains("2\tl2"));
        assertTrue(r.content().contains("3\tl3"));
        assertFalse(r.content().contains("\tl1"), "不应包含起始行之前的内容");
        assertFalse(r.content().contains("\tl4"), "不应超出 limit 范围");
    }

    @Test
    void rejectsRelativePath(@TempDir Path dir) {
        ToolResult r = read(dir, Map.of("file_path", "relative/path.txt"));
        assertTrue(r.error());
        assertTrue(r.content().contains("绝对路径"));
    }

    @Test
    void errorsOnMissingFile(@TempDir Path dir) {
        ToolResult r = read(dir, Map.of("file_path", dir.resolve("nope.txt").toString()));
        assertTrue(r.error());
        assertTrue(r.content().contains("不存在"));
    }

    @Test
    void warnsOnEmptyFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("empty.txt");
        Files.writeString(file, "");
        ToolResult r = read(dir, Map.of("file_path", file.toString()));
        assertFalse(r.error());
        assertTrue(r.content().contains("内容为空"));
    }

    @Test
    void errorsOnDirectory(@TempDir Path dir) {
        ToolResult r = read(dir, Map.of("file_path", dir.toString()));
        assertTrue(r.error());
        assertTrue(r.content().contains("目录"));
    }
}
