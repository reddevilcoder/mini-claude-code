package com.minicc.diff;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedDiffTest {

    @Test
    void singleLineChange() {
        List<String> d = UnifiedDiff.diff("a\nb\nc\n", "a\nB\nc\n", 1);
        assertTrue(d.stream().anyMatch(l -> l.startsWith("@@")));
        assertTrue(d.contains("-b"));
        assertTrue(d.contains("+B"));
        assertArrayEquals(new int[]{1, 1}, UnifiedDiff.stat("a\nb\nc\n", "a\nB\nc\n"));
    }

    @Test
    void newFileIsAllAdditions() {
        assertArrayEquals(new int[]{2, 0}, UnifiedDiff.stat("", "x\ny\n"));
    }

    @Test
    void identicalHasNoHunks() {
        assertTrue(UnifiedDiff.diff("a\nb\n", "a\nb\n", 3).isEmpty());
        assertArrayEquals(new int[]{0, 0}, UnifiedDiff.stat("a\nb\n", "a\nb\n"));
    }
}
