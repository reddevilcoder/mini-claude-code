package com.minicc.tool.builtin;

import com.minicc.agent.CancelToken;
import com.minicc.permission.PermissionState;
import com.minicc.session.TodoItem;
import com.minicc.session.TodoList;
import com.minicc.session.WorkspaceState;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TodoWriteTool 的单元测试。 */
class TodoWriteToolTest {

    private final TodoWriteTool tool = new TodoWriteTool();

    private static Map<String, Object> todo(String content, String activeForm, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", content);
        m.put("activeForm", activeForm);
        m.put("status", status);
        return m;
    }

    private ToolResult call(TodoList list, List<Map<String, Object>> todos) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("todos", todos);
        return tool.execute(ToolCallHelper.of("1", "TodoWrite", args),
                new ToolContext(Path.of("."), new WorkspaceState(), list, false, 120000L,
                        new CancelToken(), new PermissionState()));
    }

    @Test
    void replacesListAndReturnsCountSummary() {
        TodoList list = new TodoList();
        ToolResult r = call(list, List.of(
                todo("读现状", "正在读现状", "completed"),
                todo("改代码", "正在改代码", "in_progress"),
                todo("跑测试", "正在跑测试", "pending")));
        assertFalse(r.error());
        assertEquals(3, list.items().size());
        // 回填给模型的是短回执：只报计数 + 当前进行中的一项，不重复回显清单正文。
        assertTrue(r.content().contains("已更新 3 项待办"));
        assertTrue(r.content().contains("1 完成 / 1 进行中 / 1 待办"));
        assertTrue(r.content().contains("当前：正在改代码"));
        assertFalse(r.content().contains("☑"));
        assertFalse(r.content().contains("跑测试"));
    }

    @Test
    void rejectsMultipleInProgress() {
        TodoList list = new TodoList();
        ToolResult r = call(list, List.of(
                todo("A", "正在 A", "in_progress"),
                todo("B", "正在 B", "in_progress")));
        assertTrue(r.error());
        assertTrue(r.content().contains("in_progress"));
    }

    @Test
    void laterCallReplacesEarlier() {
        TodoList list = new TodoList();
        call(list, List.of(todo("A", "正在 A", "in_progress")));
        call(list, List.of(
                todo("A", "正在 A", "completed"),
                todo("B", "正在 B", "in_progress")));
        assertEquals(2, list.items().size());
        assertEquals(TodoItem.Status.COMPLETED, list.items().get(0).status());
        assertEquals(1, list.inProgressCount());
    }
}
