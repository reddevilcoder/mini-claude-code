package com.minicc.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AutoMemory 的单元测试：目录创建、MEMORY.md 读取与截断。 */
class AutoMemoryTest {

    @Test
    void ensureMemoryDirExistsCreatesMemoryDirectory(@TempDir Path dir) {
        Path memoryDir = dir.resolve("memory");
        AutoMemory auto = new AutoMemory(memoryDir);
        auto.ensureMemoryDirExists();
        assertTrue(Files.isDirectory(memoryDir), "ensureMemoryDirExists 应创建记忆目录");
    }

    @Test
    void readMemoryIndexEmptyWhenNoFile(@TempDir Path dir) {
        AutoMemory auto = new AutoMemory(dir);
        assertTrue(auto.readMemoryIndex().isEmpty(), "没有 MEMORY.md 时应返回空");
    }

    @Test
    void readMemoryIndexReturnsContentUntruncatedWhenSmall(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("MEMORY.md"), "- [A](a.md) — 一句话\n- [B](b.md) — 另一句\n");
        AutoMemory auto = new AutoMemory(dir);
        Optional<String> index = auto.readMemoryIndex();
        assertTrue(index.isPresent());
        assertTrue(index.get().contains("[A](a.md)"));
        assertFalse(index.get().contains("WARNING"), "未超限不应出现截断告警");
    }

    @Test
    void readMemoryIndexTruncatesAndWarnsWhenTooManyLines(@TempDir Path dir) throws IOException {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 250; i++) {               // 超过 200 行上限
            big.append("- [item").append(i).append("](f").append(i).append(".md) — hook\n");
        }
        Files.writeString(dir.resolve("MEMORY.md"), big.toString());
        AutoMemory auto = new AutoMemory(dir);
        String index = auto.readMemoryIndex().orElseThrow();
        assertTrue(index.contains("WARNING"), "超 200 行应出现截断告警");
        long lineCount = index.lines().count();
        assertTrue(lineCount <= 205, "截断后行数应接近 200（含告警几行），实际：" + lineCount);
        assertTrue(index.contains("[item0]"), "应从文件开头保留");
        assertFalse(index.contains("[item249]"), "超过 200 行的条目不应再读入");
    }

    @Test
    void readMemoryIndexStopsBeforeLineThatWouldExceedBytes(@TempDir Path dir) throws IOException {
        String huge = "x".repeat(AutoMemory.MAX_INDEX_BYTES);
        Files.writeString(dir.resolve("MEMORY.md"), "keep me\n" + huge + "\nalso skipped\n");
        String index = new AutoMemory(dir).readMemoryIndex().orElseThrow();
        assertTrue(index.startsWith("keep me"), "字节超限应停在上一完整行");
        assertTrue(index.contains("WARNING"));
        assertFalse(index.contains(huge), "超长行本身不应进入上下文");
    }
}
