package com.minicc.persistence;

import com.minicc.tool.ToolCallHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SessionStore 的单元测试：append-only 写入、读回、消息序列化、损坏行容错、disabled 空操作。 */
class SessionStoreTest {

    @Test
    void appendAndReadBackRoundTrips(@TempDir Path dir) {
        Path log = dir.resolve("s.jsonl");
        SessionStore store = new SessionStore(log);

        UserMessage user = new UserMessage("给下单接口加校验");
        AssistantMessage assistant = AssistantMessage.builder()
                .content("先看现状")
                .toolCalls(List.of(ToolCallHelper.of("c1", "Read", Map.of("file_path", "/x/A.java"))))
                .build();
        ToolResponseMessage tool = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "Read", "120 行")))
                .build();
        store.append(user);
        store.append(assistant);
        store.append(tool);

        List<Message> messages = store.readAll();
        assertEquals(3, messages.size());
        assertEquals(user, messages.get(0));
        assertEquals(assistant, messages.get(1));
        assertEquals(tool, messages.get(2));

        AssistantMessage readBack = assertInstanceOf(AssistantMessage.class, messages.get(1));
        assertEquals("/x/A.java", ToolCallHelper.arguments(readBack.getToolCalls().get(0)).get("file_path"));
    }

    @Test
    void roundTripsReasoningContent(@TempDir Path dir) {
        Path log = dir.resolve("s.jsonl");
        SessionStore store = new SessionStore(log);
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .properties(Map.of(SessionStore.REASONING_CONTENT, "先在仓库里搜 skill。"))
                .toolCalls(List.of(ToolCallHelper.of("c1", "Grep", Map.of("pattern", "skill"))))
                .build();

        store.append(assistant);

        AssistantMessage readBack = assertInstanceOf(AssistantMessage.class, store.readAll().get(0));
        assertEquals(assistant, readBack);
        assertEquals("先在仓库里搜 skill。", readBack.getMetadata().get(SessionStore.REASONING_CONTENT));
    }

    @Test
    void writesOneJsonPerLineWithTypeDiscriminator(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("s.jsonl");
        SessionStore store = new SessionStore(log);
        store.append(new UserMessage("hi"));
        store.append(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "Bash", "ok")))
                .build());

        List<String> lines = Files.readAllLines(log);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"messageType\":\"USER\""));
        assertTrue(lines.get(1).contains("\"messageType\":\"TOOL\""));
    }

    @Test
    void skipsCorruptTrailingLine(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("s.jsonl");
        SessionStore store = new SessionStore(log);
        store.append(new UserMessage("hi"));
        // 模拟崩溃留下的半行 JSON（append 中途被杀）。
        Files.writeString(log, "{\"messageType\":\"USER\",\"tex",
                java.nio.file.StandardOpenOption.APPEND);

        List<Message> messages = store.readAll();
        assertEquals(1, messages.size(), "损坏的末行应被跳过，前面完整的仍恢复");
    }

    @Test
    void disabledStoreIsNoOp(@TempDir Path dir) {
        Path log = dir.resolve("s.jsonl");
        SessionStore store = SessionStore.disabled();
        store.append(new UserMessage("hi"));
        assertFalse(Files.exists(log), "disabled 存储不应产生日志文件");
        assertTrue(store.readAll().isEmpty());
    }
}
