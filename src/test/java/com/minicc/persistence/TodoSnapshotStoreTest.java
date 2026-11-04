package com.minicc.persistence;

import com.minicc.session.TodoItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TodoSnapshotStore 的单元测试：整份覆盖写、读回、disabled 空操作。 */
class TodoSnapshotStoreTest {

    @Test
    void writeReadRoundTripsAndOverwrites(@TempDir Path dir) {
        Path file = dir.resolve("s.todos.json");
        TodoSnapshotStore store = new TodoSnapshotStore(file);

        store.write(List.of(
                new TodoItem("读现状", "正在读现状", TodoItem.Status.IN_PROGRESS),
                new TodoItem("加校验", "正在加校验", TodoItem.Status.PENDING)));
        List<TodoItem> first = store.read();
        assertEquals(2, first.size());
        assertEquals(TodoItem.Status.IN_PROGRESS, first.get(0).status());

        // 整份覆盖：再写一份更短的，读回应是最新态。
        store.write(List.of(new TodoItem("完成", "正在完成", TodoItem.Status.COMPLETED)));
        List<TodoItem> second = store.read();
        assertEquals(1, second.size());
        assertEquals(TodoItem.Status.COMPLETED, second.get(0).status());
    }

    @Test
    void disabledStoreIsNoOp(@TempDir Path dir) {
        Path file = dir.resolve("s.todos.json");
        TodoSnapshotStore store = TodoSnapshotStore.disabled();
        store.write(List.of(new TodoItem("x", "x", TodoItem.Status.PENDING)));
        assertFalse(Files.exists(file));
        assertTrue(store.read().isEmpty());
    }
}
