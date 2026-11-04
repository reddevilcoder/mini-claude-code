package com.minicc.persistence;

import com.minicc.session.Session;
import com.minicc.session.TodoItem;
import com.minicc.tool.ToolCallHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SessionLoader 的单元测试：回放重建、中断检测（摘掉悬空工具调用）、待办恢复、续写同一文件。 */
class SessionLoaderTest {

    @Test
    void restoresHistoryAndTodosAndContinuesSameFile(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("s.jsonl");
        Path todoFile = dir.resolve("s.todos.json");
        SessionStore store = new SessionStore(log);
        TodoSnapshotStore todoStore = new TodoSnapshotStore(todoFile);

        // 写入一段会话：user → assistant(调用 c1) → tool result(c1) → assistant(悬空 c2，无文本)
        store.append(new UserMessage("给下单接口加校验"));
        store.append(AssistantMessage.builder()
                .content("先读")
                .toolCalls(List.of(ToolCallHelper.of("c1", "Read", Map.of("file_path", "/x/A.java"))))
                .build());
        store.append(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "Read", "120 行")))
                .build());
        store.append(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(ToolCallHelper.of("c2", "Edit", Map.of("file_path", "/x/A.java"))))
                .build());
        todoStore.write(List.of(
                new TodoItem("读现状", "正在读现状", TodoItem.Status.COMPLETED),
                new TodoItem("加校验", "正在加校验", TodoItem.Status.IN_PROGRESS)));

        // 恢复进一个全新的 Session。
        Session session = new Session(dir);
        SessionLoader.Result result = new SessionLoader().restore(session, "s", store, todoStore);

        // 悬空的 assistant(c2，无文本) 被摘掉：恢复出 3 条消息。
        assertEquals(3, result.messageCount());
        assertEquals(3, session.size());
        assertEquals(2, result.todos().size());

        // 续写：新消息应追加到被恢复的同一个 .jsonl（续旧账本）。
        long before = Files.readAllLines(log).size();
        session.appendUserMessage("继续");
        long after = Files.readAllLines(log).size();
        assertEquals(before + 1, after, "恢复后新消息应追加到同一个日志文件");
        assertEquals("s", session.sessionId());
    }

    @Test
    void keepsAssistantTextButDropsDanglingCall(@TempDir Path dir) {
        Path log = dir.resolve("s.jsonl");
        SessionStore store = new SessionStore(log);
        store.append(new UserMessage("hi"));
        // 助手有文本，但工具调用悬空（无结果）：应保留这条助手消息、但摘掉悬空调用。
        store.append(AssistantMessage.builder()
                .content("我打算这样做")
                .toolCalls(List.of(ToolCallHelper.of("cX", "Bash", Map.of("command", "sleep 30"))))
                .build());

        Session session = new Session(dir);
        SessionLoader.Result result = new SessionLoader().restore(session,
                "s", store, TodoSnapshotStore.disabled());

        assertEquals(2, result.messageCount(), "user + 带文本的 assistant（悬空调用已摘）");
    }

    @Test
    void keepsReasoningWhenDroppingDanglingCall(@TempDir Path dir) {
        Path log = dir.resolve("s.jsonl");
        SessionStore store = new SessionStore(log);
        store.append(new UserMessage("hi"));
        store.append(AssistantMessage.builder()
                .content("先看再改")
                .properties(Map.of(SessionStore.REASONING_CONTENT, "Read 有结果，Bash 被打断了。"))
                .toolCalls(List.of(
                        ToolCallHelper.of("c1", "Read", Map.of("file_path", "/x/A.java")),
                        ToolCallHelper.of("cX", "Bash", Map.of("command", "sleep 30"))))
                .build());
        store.append(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "Read", "120 行")))
                .build());

        Session session = new Session(dir);
        new SessionLoader().restore(session, "s", store, TodoSnapshotStore.disabled());

        AssistantMessage assistant = (AssistantMessage) session.history().get(1);
        assertEquals(List.of("c1"), assistant.getToolCalls().stream().map(AssistantMessage.ToolCall::id).toList());
        assertEquals("Read 有结果，Bash 被打断了。", assistant.getMetadata().get(SessionStore.REASONING_CONTENT));
    }

    @Test
    void emptyLogLeavesCurrentSessionUntouched(@TempDir Path dir) {
        Path log = dir.resolve("empty.jsonl");
        SessionStore store = new SessionStore(log);

        Session session = new Session(dir);
        session.appendUserMessage("当前会话里已有内容");
        int sizeBefore = session.size();

        SessionLoader.Result result = new SessionLoader().restore(session,
                "empty", store, TodoSnapshotStore.disabled());

        assertEquals(0, result.messageCount());
        assertEquals(sizeBefore, session.size(), "无可恢复内容时不应清空当前会话");
        assertTrue(result.todos().isEmpty());
    }
}
