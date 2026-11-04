package com.minicc.session;

import com.minicc.persistence.SessionStore;
import com.minicc.persistence.TodoSnapshotStore;
import com.minicc.tool.ToolCallHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Session 把模型返回的 AssistantMessage 原样写入历史，工具调用也原样落盘。 */
class SessionTest {

    @Test
    void appendAssistantKeepsTheMessageAndPersistsCalls(@TempDir Path dir) {
        Path log = dir.resolve("s.jsonl");
        SessionStore store = new SessionStore(log);
        Session session = new Session(dir);
        session.attachPersistence("sid", store, TodoSnapshotStore.disabled());

        AssistantMessage message = AssistantMessage.builder()
                .content("先看文件")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "c1", "function", "Read", "{\"file_path\":\"A.java\"}")))
                .build();
        session.appendAssistant(message);

        assertEquals(1, session.size());
        assertSame(message, session.history().get(0));

        AssistantMessage persisted = assertInstanceOf(AssistantMessage.class, store.readAll().get(0));
        assertEquals(message, persisted);
        assertEquals("A.java", ToolCallHelper.arguments(persisted.getToolCalls().get(0)).get("file_path"));
    }

    @Test
    void blankOrInvalidArgumentsReadAsEmpty(@TempDir Path dir) {
        Session session = new Session(dir);
        AssistantMessage message = AssistantMessage.builder()
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-2", "function", "Read", "  "),
                        new AssistantMessage.ToolCall("call-3", "function", "Read", "not-json")))
                .build();

        session.appendAssistant(message);

        assertSame(message, session.history().get(0));
        assertEquals(Map.of(), ToolCallHelper.arguments(message.getToolCalls().get(0)));
        assertEquals(Map.of(), ToolCallHelper.arguments(message.getToolCalls().get(1)));
        assertEquals("call-2", message.getToolCalls().get(0).id());
        assertEquals("Read", message.getToolCalls().get(1).name());
    }
}
