package com.minicc.session;

import com.minicc.persistence.SessionStore;
import com.minicc.persistence.TodoSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** M10：turnId 持久化、REWIND 事件、压缩后回退仍以 JSONL 为准、/clear 清读标记。 */
class SessionRewindTest {

    private static Session session(Path dir, SessionStore store) {
        Session s = new Session(dir);
        s.attachPersistence("sid", store, TodoSnapshotStore.disabled());
        return s;
    }

    @Test
    void rewindTruncatesAndSurvivesReload(@TempDir Path dir) {
        SessionStore store = new SessionStore(dir.resolve("s.jsonl"));
        Session s = session(dir, store);
        s.appendUserMessage("first");
        s.appendAssistant(AssistantMessage.builder().content("ok1").build());
        s.appendUserMessage("second");
        String t2 = s.currentTurnId();
        s.appendAssistant(AssistantMessage.builder().content("ok2").build());

        assertEquals(2, s.turns().size());
        assertEquals("second", s.rewindConversation(t2));
        assertEquals(2, s.size());
        assertEquals(1, s.turns().size());

        List<Message> reloaded = new SessionStore(dir.resolve("s.jsonl")).readAll();
        assertEquals(2, reloaded.size());
        UserMessage u = assertInstanceOf(UserMessage.class, reloaded.get(0));
        assertNotNull(u.getMetadata().get(SessionStore.TURN_ID));
    }

    @Test
    void rewindAfterCompactionUsesJsonl(@TempDir Path dir) {
        SessionStore store = new SessionStore(dir.resolve("s.jsonl"));
        Session s = session(dir, store);
        s.appendUserMessage("first");
        String t1 = s.currentTurnId();
        s.appendAssistant(AssistantMessage.builder().content("ok1").build());
        s.appendUserMessage("second");
        s.replaceHistoryInMemory(List.of(new UserMessage("summary")));   // 模拟压缩

        assertEquals("first", s.rewindConversation(t1));
        assertEquals(0, s.size());
        assertNull(s.rewindConversation("nope"));
    }

    @Test
    void clearForgetsReadMarkers(@TempDir Path dir) {
        Session s = session(dir, SessionStore.disabled());
        Path f = dir.resolve("a.txt");
        s.workspace().markRead(f);
        s.clear();
        assertFalse(s.workspace().hasRead(f));
    }
}
