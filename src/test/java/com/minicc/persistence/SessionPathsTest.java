package com.minicc.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SessionPaths 的单元测试：路径清洗、文件名拼接、最近一次会话选择。 */
class SessionPathsTest {

    @Test
    void sanitizesProjectPathIntoDirName(@TempDir Path state) {
        SessionPaths paths = new SessionPaths(state);
        Path projectDir = paths.projectDir(Path.of("/Users/wangzheng/work/orders"));
        String name = projectDir.getFileName().toString();
        assertTrue(name.matches("[a-zA-Z0-9-]+"), "目录名应只含字母数字和 -，实际：" + name);
        assertEquals(state.resolve("projects"), projectDir.getParent());
    }

    @Test
    void buildsLogAndSnapshotFileNames(@TempDir Path state) {
        SessionPaths paths = new SessionPaths(state);
        Path root = Path.of("/tmp/proj");
        assertTrue(paths.sessionLog(root, "abc").getFileName().toString().equals("abc.jsonl"));
        assertTrue(paths.todoSnapshot(root, "abc").getFileName().toString().equals("abc.todos.json"));
    }

    @Test
    void latestSessionIdPicksMostRecentlyModified(@TempDir Path state) throws Exception {
        SessionPaths paths = new SessionPaths(state);
        Path root = Path.of("/tmp/proj");
        Path dir = paths.projectDir(root);
        Files.createDirectories(dir);

        Path older = dir.resolve("old.jsonl");
        Path newer = dir.resolve("new.jsonl");
        Files.writeString(older, "{}\n");
        Files.writeString(newer, "{}\n");
        older.toFile().setLastModified(1_000_000L);
        newer.toFile().setLastModified(2_000_000L);

        Optional<String> latest = paths.latestSessionId(root);
        assertEquals(Optional.of("new"), latest);
    }

    @Test
    void latestSessionIdEmptyWhenNoProjectDir(@TempDir Path state) {
        SessionPaths paths = new SessionPaths(state);
        assertTrue(paths.latestSessionId(Path.of("/tmp/never-used")).isEmpty());
    }

    @Test
    void memoryDirHangsUnderProjectDir(@TempDir Path state) {
        SessionPaths paths = new SessionPaths(state);
        Path root = Path.of("/Users/wangzheng/work/orders");
        Path memory = paths.memoryDir(root);
        assertEquals("memory", memory.getFileName().toString());
        assertEquals(paths.projectDir(root), memory.getParent(), "记忆目录应挂在本项目的会话目录下");
    }
}
