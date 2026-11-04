package com.minicc.compact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第①层工具结果预算的单元测试：超阈值落盘换预览+路径；Read(-1) 永不落盘。 */
class ToolResultCompactorTest {

    private ToolResultCompactor compactor(Path dir) {
        // Bash 阈值 10 字符（便于测试），Read=-1 无限，默认 50。
        return new ToolResultCompactor(dir, Map.of("Bash", 10, "Read", -1), 50);
    }

    @Test
    void offloadsLargeResultAndReplacesWithPreviewAndPath(@TempDir Path dir) throws IOException {
        String big = "0123456789".repeat(1000); // 10000 字符，远超阈值与 2KB 预览
        ToolResponseMessage in = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "Bash", big)))
                .build();

        ToolResponseMessage out = compactor(dir).apply(in);
        String stored = out.getResponses().get(0).responseData();

        // 入库那条换成了"引用消息"（预览+路径），不再是完整原文。
        assertTrue(stored.startsWith("<工具结果过大"), "应替换为引用消息");
        assertTrue(stored.contains("可用 Read 读取"), "应提示可用 Read 取回");
        assertFalse(stored.contains(big), "入库内容不应再包含完整原文");

        // 完整原文落到 tool-results/<callId>.txt。
        Path file = dir.resolve("call-1.txt");
        assertTrue(Files.exists(file), "完整原文应落盘");
        assertEquals(big, Files.readString(file), "落盘内容应是完整原文");
    }

    @Test
    void readNeverOffloaded(@TempDir Path dir) {
        String big = "x".repeat(10_000);
        ToolResponseMessage in = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-read", "Read", big)))
                .build();

        ToolResponseMessage out = compactor(dir).apply(in);
        // Read 阈值 -1：原样返回，不落盘、不替换（避免"拿到路径又 Read"的死循环）。
        assertEquals(big, out.getResponses().get(0).responseData());
        assertFalse(Files.exists(dir.resolve("call-read.txt")));
    }

    @Test
    void smallResultUntouched(@TempDir Path dir) {
        ToolResponseMessage in = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c", "Bash", "ok")))
                .build();
        ToolResponseMessage out = compactor(dir).apply(in);
        assertEquals("ok", out.getResponses().get(0).responseData());
    }
}
