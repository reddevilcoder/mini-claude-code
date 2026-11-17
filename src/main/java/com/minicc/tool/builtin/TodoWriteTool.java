package com.minicc.tool.builtin;

import com.minicc.session.TodoItem;
import com.minicc.tool.Schemas;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TodoWrite 工具：维护本次任务的待办清单，跟踪多步任务进度。
 *
 * <p>它维护的是"工作状态"，不碰文件和环境，
 * 因此无需审批、无需沙箱（{@link #isReadOnly()} 返回 true）。
 *
 * <p>语义：用新清单整体替换旧清单（全量覆盖，而非增量）。实际校验“至多一个进行中”，允许零项进行中。
 *
 * <p><b>面试关注：为什么先完整解析、校验，再一次替换？</b>中途发现某项不合法时保留原清单，
 * 避免边解析边更新导致部分成功。全量覆盖也无需给每项维护稳定 ID 或实现增量合并，代价是每次调用须发送完整列表。
 * 此工具虽免文件/命令审批，却会修改共享会话状态，所以不可并行。这里的待办是进度表达，
 * 不是任务执行引擎：标记 completed 不会实际执行测试，也不证明对应工作已经完成。
 */
public class TodoWriteTool implements Tool {

    /** 工具名。AgentLoop 用它判断"这一轮要不要额外渲染清单给人看"。 */
    public static final String NAME = "TodoWrite";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                为当前编码会话维护一份结构化的待办清单，用来跟踪进度、组织复杂任务。
                何时使用：任务有 3 步及以上、用户一次给了多项任务、刚接到新需求时立即记录。
                何时不用：单步琐碎任务、纯问答。
                规则：实时更新状态；完成一项立即标 completed；任意时刻恰好一个 in_progress；
                每项都要同时给 content（祈使形，如"运行测试"）和 activeForm（进行时，如"正在运行测试"）。
                每次调用都用新清单整体替换旧清单。""";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        // todos: 数组，每个元素是 {content, activeForm, status}
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("content", Schemas.prop("string", "祈使形描述，该做什么"));
        itemProps.put("activeForm", Schemas.prop("string", "进行时描述，执行时展示"));
        itemProps.put("status", Schemas.prop("string", "状态",
                List.of("pending", "in_progress", "completed")));
        item.put("properties", itemProps);
        item.put("required", List.of("content", "activeForm", "status"));

        Map<String, Object> todos = new LinkedHashMap<>();
        todos.put("type", "array");
        todos.put("description", "完整的待办清单（整体替换旧清单）");
        todos.put("items", item);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("todos", todos);
        return Schemas.object(props, List.of("todos"));
    }

    @Override
    public boolean isReadOnly() {
        return true; // 只改会话内的工作状态，不碰文件/环境，无需审批
    }

    @Override
    public boolean isConcurrencySafe() {
        return false; // 改共享的会话状态，不并行
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(AssistantMessage.ToolCall call, ToolContext ctx) {
        Object raw = ToolCallHelper.arguments(call).get("todos");
        if (!(raw instanceof List<?> list)) {
            return ToolResult.error("参数 todos 必须是一个数组。");
        }

        List<TodoItem> parsed = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                return ToolResult.error("todos 的每个元素必须是对象 {content, activeForm, status}。");
            }
            String content = str(m.get("content"));
            String activeForm = str(m.get("activeForm"));
            if (content.isBlank()) {
                return ToolResult.error("每条待办都必须有 content。");
            }
            if (activeForm.isBlank()) {
                activeForm = content; // 容错：没给进行时就用祈使形兜底
            }
            parsed.add(new TodoItem(content, activeForm, TodoItem.Status.from(str(m.get("status")))));
        }

        // 提示词建议“恰好一个”，实际约束更宽：只拒绝多个进行中，全部 pending 或 completed 同样合法。
        long inProgress = parsed.stream()
                .filter(i -> i.status() == TodoItem.Status.IN_PROGRESS).count();
        if (inProgress > 1) {
            return ToolResult.error("同时有 " + inProgress + " 项处于 in_progress；任意时刻应恰好一项进行中，请修正。");
        }

        ctx.todos().replace(parsed);
        return ToolResult.ok(summary(parsed));
    }

    /**
     * 回填给模型的短回执：只说"生效了几项、各状态几项"。
     *
     * <p>不重复回显清单正文——模型上一轮刚把完整 todos 参数发出来，那份数据已经在上下文里，
     * 每步重贴一遍纯属重复占 token。这里保留计数，是为了让模型能发现自己写错了状态。
     * 给人看的勾选清单由 {@code Renderer.todoList} 单独渲染。
     */
    private static String summary(List<TodoItem> items) {
        if (items.isEmpty()) {
            return "待办清单已清空。";
        }
        long completed = items.stream().filter(i -> i.status() == TodoItem.Status.COMPLETED).count();
        long inProgress = items.stream().filter(i -> i.status() == TodoItem.Status.IN_PROGRESS).count();
        long pending = items.size() - completed - inProgress;

        StringBuilder sb = new StringBuilder("已更新 ").append(items.size()).append(" 项待办：")
                .append(completed).append(" 完成 / ")
                .append(inProgress).append(" 进行中 / ")
                .append(pending).append(" 待办");
        items.stream()
                .filter(i -> i.status() == TodoItem.Status.IN_PROGRESS)
                .findFirst()
                .ifPresent(i -> sb.append("；当前：").append(i.activeForm()));
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
