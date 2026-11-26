package com.minicc.headless;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessOptionsTest {

    @Test
    void legacyDirectoryArgumentStaysInteractive() {
        HeadlessOptions o = HeadlessOptions.parse(new String[]{"/tmp/proj"});
        assertFalse(o.isHeadless());
        assertEquals(Path.of("/tmp/proj"), o.workingDir());
    }

    @Test
    void parsesHeadlessFlags() {
        HeadlessOptions o = HeadlessOptions.parse(new String[]{
                "-p", "fix it", "--dir", "/tmp/p", "--output", "json", "--permission", "auto",
                "--max-steps", "7", "--bare", "-q"});
        assertTrue(o.isHeadless());
        assertTrue(o.jsonOutput());
        assertEquals(HeadlessInteraction.PermissionMode.AUTO, o.permission());
        assertEquals(7, o.maxSteps());
        assertTrue(o.bare());
        assertTrue(o.quiet());
    }

    @Test
    void defaultsToReadonly() {
        assertEquals(HeadlessInteraction.PermissionMode.READONLY,
                HeadlessOptions.parse(new String[]{"-p", "x"}).permission());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> HeadlessOptions.parse(new String[]{"--nope"}));
        assertThrows(IllegalArgumentException.class, () -> HeadlessOptions.parse(new String[]{"-p"}));
        assertThrows(IllegalArgumentException.class,
                () -> HeadlessOptions.parse(new String[]{"-p", "x", "--permission", "yolo"}));
        assertThrows(IllegalArgumentException.class,
                () -> HeadlessOptions.parse(new String[]{"-p", "x", "--max-steps", "0"}));
    }
}
