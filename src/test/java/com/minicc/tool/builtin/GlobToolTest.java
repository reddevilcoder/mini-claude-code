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
import java.nio.file.attribute.FileTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GlobTool 的单元测试。 */
class GlobToolTest {

    private final GlobTool tool = new GlobTool();

    private ToolResult glob(Path dir, Map<String, Object> args) {
        return tool.execute(ToolCallHelper.of("1", "Glob", args),
                new ToolContext(dir, new WorkspaceState(), new TodoList(), false, 120000L,
                        new CancelToken(), new PermissionState()));
    }

    @Test
    void matchesByPattern(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main"));
        Files.writeString(dir.resolve("src/main/A.java"), "class A {}");
        Files.writeString(dir.resolve("src/main/B.java"), "class B {}");
        Files.writeString(dir.resolve("src/main/c.txt"), "text");

        ToolResult r = glob(dir, Map.of("pattern", "**/*.java"));
        assertFalse(r.error());
        assertTrue(r.content().contains("A.java"));
        assertTrue(r.content().contains("B.java"));
        assertFalse(r.content().contains("c.txt"), "txt 不匹配 *.java");
    }

    @Test
    void sortsByModifiedTimeDescending(@TempDir Path dir) throws IOException {
        Path older = dir.resolve("older.java");
        Path newer = dir.resolve("newer.java");
        Files.writeString(older, "old");
        Files.writeString(newer, "new");
        // 让 newer 的修改时间明显更晚。
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000_000L));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000_000L));

        ToolResult r = glob(dir, Map.of("pattern", "*.java"));
        assertFalse(r.error());
        int idxNewer = r.content().indexOf("newer.java");
        int idxOlder = r.content().indexOf("older.java");
        assertTrue(idxNewer >= 0 && idxOlder >= 0);
        assertTrue(idxNewer < idxOlder, "最近修改的文件应排在前面");
    }

    @Test
    void reportsNoMatches(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "x");
        ToolResult r = glob(dir, Map.of("pattern", "**/*.kt"));
        assertFalse(r.error());
        assertTrue(r.content().contains("没有匹配"));
    }
}
