package com.minicc.session;

import java.util.ArrayList;
import java.util.List;

/**
 * 待办清单：本次任务的工作状态，用来让多步任务不漏步、不跑偏。
 *
 * <p>语义：用新清单整体替换旧清单（全量覆盖，而非增量）。
 * 它随任务存在，任务做完就不再需要；和 M7/M8 的跨会话长期记忆不是一回事。
 *
 * <p><b>面试关注：为什么全量替换？</b>模型每次提交一个完整计划，避免增量操作需要处理的条目 ID、
 * 补丁顺序和重复应用问题。代价是清单变大后每次都要传全量，遗漏旧条目也会直接删除它。
 * 本类只存状态，“进行中条目数量”等业务校验由 TodoWriteTool 完成；直接调用 replace 不会校验。
 * ArrayList 按写工具串行执行的约定使用，本类本身不提供并发修改保护。
 */
public class TodoList {

    private final List<TodoItem> items = new ArrayList<>();

    /** 用新清单整体替换旧清单。 */
    public void replace(List<TodoItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
    }

    /** 当前清单（只读副本）。 */
    public List<TodoItem> items() {
        return List.copyOf(items);
    }

    /** 处于"进行中"的待办数量（用于校验"恰好一个进行中"）。 */
    public long inProgressCount() {
        return items.stream().filter(i -> i.status() == TodoItem.Status.IN_PROGRESS).count();
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** 清空（会话清空时调用）。 */
    public void clear() {
        items.clear();
    }
}
