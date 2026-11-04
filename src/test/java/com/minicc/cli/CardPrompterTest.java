package com.minicc.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 卡片编号解析：不依赖终端，只验证"输入一行 → 下标"这一步。 */
class CardPrompterTest {

    @Test
    void parseSingleAcceptsInRangeNumber() {
        assertEquals(0, CardPrompter.parseSingle("1", 3));
        assertEquals(2, CardPrompter.parseSingle(" 3 ", 3));
    }

    @Test
    void parseSingleRejectsOutOfRangeAndJunk() {
        assertNull(CardPrompter.parseSingle("0", 3));
        assertNull(CardPrompter.parseSingle("4", 3));
        assertNull(CardPrompter.parseSingle("a", 3));
        assertNull(CardPrompter.parseSingle("", 3));
        assertNull(CardPrompter.parseSingle("1,2", 3));
    }

    @Test
    void parseMultiSplitsAndDedups() {
        assertEquals(List.of(0, 2), CardPrompter.parseMulti("1,3", 4));
        assertEquals(List.of(0, 2), CardPrompter.parseMulti("1，3", 4));
        assertEquals(List.of(1), CardPrompter.parseMulti("2 2", 4));
    }

    @Test
    void parseMultiRejectsAnyInvalidToken() {
        assertNull(CardPrompter.parseMulti("1,9", 3));
        assertNull(CardPrompter.parseMulti("x", 3));
        assertNull(CardPrompter.parseMulti("   ", 3));
    }
}
