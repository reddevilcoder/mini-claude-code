package com.minicc.checkpoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointStoreTest {

    private static void write(CheckpointStore cp, String turn, Path f, String content) throws Exception {
        cp.beforeWrite(turn, f);
        Files.writeString(f, content);
        cp.afterWrite(turn, f);
    }

    @Test
    void restoresExistingAndDeletesNewFiles(@TempDir Path dir) throws Exception {
        CheckpointStore cp = new CheckpointStore(dir.resolve("cp"));
        Path a = dir.resolve("a.txt");
        Path b = dir.resolve("b.txt");
        Files.writeString(a, "v0");
        cp.registerTurn("t1");
        write(cp, "t1", a, "v1");
        write(cp, "t1", b, "new");
        cp.registerTurn("t2");
        write(cp, "t2", a, "v2");

        CheckpointStore.RestoreReport r = cp.restoreFrom("t1", false);
        assertEquals("v0", Files.readString(a));   // 用最早一轮的备份
        assertFalse(Files.exists(b));
        assertEquals(List.of(a), r.restored());
        assertEquals(List.of(b), r.deleted());
        assertTrue(r.conflicts().isEmpty());
    }

    @Test
    void restoreFromLaterTurnKeepsEarlierChanges(@TempDir Path dir) throws Exception {
        CheckpointStore cp = new CheckpointStore(dir.resolve("cp"));
        Path a = dir.resolve("a.txt");
        Files.writeString(a, "v0");
        cp.registerTurn("t1");
        write(cp, "t1", a, "v1");
        cp.registerTurn("t2");
        write(cp, "t2", a, "v2");
        cp.restoreFrom("t2", false);
        assertEquals("v1", Files.readString(a));
    }

    @Test
    void detectsConflictsAndSkipsUnlessForced(@TempDir Path dir) throws Exception {
        CheckpointStore cp = new CheckpointStore(dir.resolve("cp"));
        Path a = dir.resolve("a.txt");
        Files.writeString(a, "v0");
        cp.registerTurn("t1");
        write(cp, "t1", a, "v1");
        Files.writeString(a, "user edit");

        CheckpointStore.RestoreReport r = cp.restoreFrom("t1", false);
        assertEquals(List.of(a), r.conflicts());
        assertEquals("user edit", Files.readString(a));

        cp.restoreFrom("t1", true);
        assertEquals("v0", Files.readString(a));
    }

    @Test
    void recordsBashAndDropsTurns(@TempDir Path dir) throws Exception {
        CheckpointStore cp = new CheckpointStore(dir.resolve("cp"));
        Path a = dir.resolve("a.txt");
        cp.registerTurn("t1");
        cp.registerTurn("t2");
        cp.noteBash("t2", "rm -rf build");
        write(cp, "t2", a, "x");
        assertTrue(cp.hasChanges("t2"));
        assertFalse(cp.hasChanges("t1"));
        assertEquals(List.of("rm -rf build"), cp.summarizeFrom("t1").bashCommands);

        cp.dropFrom("t2");
        assertEquals(List.of("t1"), cp.turns());
        assertEquals(-1, cp.indexOf("t2"));
    }
}
