package com.minicc.tool.builtin;

import com.minicc.agent.SubAgentRunner;
import com.minicc.tool.Schemas;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 派生子代理的工具（M12）。拆成两个工具而不是一个带 type 参数的工具，原因是调度与审批都按“工具”决策：
 * <ul>
 *   <li>{@code Task}（explore / plan）：只读 + 并发安全——模型一次发多个 Task 时由 ToolScheduler 并行跑，
 *       不弹审批，Plan Mode 下也可用；</li>
 *   <li>{@code GeneralTask}：非只读、串行——派生前需用户批准（可本会话放行），Plan Mode 下被拦；
 *       子代理内部的每个写操作仍各自走审批。</li>
 * </ul>
 */
public final class TaskTool implements Tool {

    public static final String NAME = "Task";
    public static final String GENERAL_NAME = "GeneralTask";

    private final SubAgentRunner runner;
    private final boolean general;

    private TaskTool(SubAgentRunner runner, boolean general) {
        this.runner = runner;
        this.general = general;
    }

    public static TaskTool readOnly(SubAgentRunner runner) {
        return new TaskTool(runner, false);
    }

    public static TaskTool general(SubAgentRunner runner) {
        return new TaskTool(runner, true);
    }

    @Override
    public String name() {
        return general ? GENERAL_NAME : NAME;
    }

    @Override
    public String description() {
        if (general) {
            return "派生一个通用子代理，在独立上下文中完成一个可以修改文件/执行命令的子任务，只返回结果汇报。"
                    + "适合边界清晰、步骤多、会产生大量中间输出的子任务。子代理看不到当前对话，prompt 必须自包含。";
        }
        return "派生只读子代理在独立上下文里调研，只返回结论，节省主上下文。"
                + "subagent_type=explore：搜索/定位代码、回答“X 在哪里、怎么实现的”；"
                + "plan：调研后给出实施方案。可在一次回复中发起多个 Task 并行调研不同方向。"
                + "子代理看不到当前对话，prompt 必须自包含（写清目标、已知线索、期望输出）。";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("description", Schemas.prop("string", "3-8 个字的任务简述（用于展示）"));
        props.put("prompt", Schemas.prop("string", "交给子代理的完整任务说明"));
        if (!general) {
            props.put("subagent_type", Schemas.prop("string", "子代理类型", List.of("explore", "plan")));
            return Schemas.object(props, List.of("description", "prompt", "subagent_type"));
        }
        return Schemas.object(props, List.of("description", "prompt"));
    }

    @Override
    public boolean isReadOnly() {
        return !general;
    }

    @Override
    public boolean isConcurrencySafe() {
        return !general;
    }

    @Override
    public int maxResultChars() {
        return 20_000;
    }

    @Override
    public ToolResult execute(AssistantMessage.ToolCall call, ToolContext ctx) {
        String prompt = ToolCallHelper.getString(call, "prompt", "").trim();
        if (prompt.isEmpty()) {
            return ToolResult.error("prompt 不能为空");
        }
        SubAgentRunner.Type type;
        if (general) {
            type = SubAgentRunner.Type.GENERAL;
        } else {
            type = SubAgentRunner.Type.parse(ToolCallHelper.getString(call, "subagent_type", "explore"));
            if (type == null || type == SubAgentRunner.Type.GENERAL) {
                return ToolResult.error("subagent_type 只能是 explore 或 plan；需要修改文件请用 " + GENERAL_NAME);
            }
        }
        try {
            SubAgentRunner.Outcome o = runner.run(type, prompt, ctx);
            String header = "[子代理 " + type.id() + "：" + o.steps() + " 步，" + o.toolCalls() + " 次工具调用"
                    + (o.completed() ? "" : "，未正常完成") + "]\n";
            return o.completed() ? ToolResult.ok(header + o.text()) : ToolResult.error(header + o.text());
        } catch (RuntimeException e) {
            return ToolResult.error("子代理失败：" + e.getMessage());
        }
    }
}
