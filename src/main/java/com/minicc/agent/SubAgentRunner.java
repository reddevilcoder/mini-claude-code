package com.minicc.agent;

import com.minicc.config.MccConfig;
import com.minicc.model.ModelClient;
import com.minicc.session.TodoList;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolRegistry;
import com.minicc.tool.ToolResult;
import com.minicc.tool.hook.HookChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 子代理执行器（M12）：在独立的消息上下文里跑一个有界的 ReAct 循环，只把最终结论交回主代理。
 *
 * <p>设计要点（面试关注）：
 * <ul>
 *   <li><b>上下文隔离</b>：子代理的中间读文件、搜索结果不进主会话，主上下文只多一条工具结果——
 *       这是子代理省 Token 的根本原因；</li>
 *   <li><b>不流式</b>：多个 Explore 可能并行，流式输出会在终端交错，所以缓冲到结束只返回结论；</li>
 *   <li><b>深度 = 1</b>：子代理的工具表里没有 Task 类工具，杜绝递归派生；</li>
 *   <li><b>独立的过滤工具表</b>：Explore/Plan 只拿只读工具，从结构上保证不会弹审批；
 *       General 复用主钩子链（Plan Mode + 审批）与主 CancelToken，写操作照常经用户批准；</li>
 *   <li><b>检查点</b>：General 的写入记到主会话当前轮次（共享 ctx.checkpoint()），/rewind 可一并撤销；</li>
 *   <li><b>已读状态</b>：子代理拿主代理已读集合的<b>副本</b>——继承“主代理读过”，
 *       但子代理读过的文件不回流（主模型并没看到内容，不能据此跳过 Read 直接 Edit）；</li>
 *   <li><b>用量</b>：共用同一个 ModelClient，用量自动累计到主会话（UsageTracker 基于原子计数，线程安全）。</li>
 * </ul>
 */
public final class SubAgentRunner {

    /** 子代理类型。 */
    public enum Type {
        EXPLORE("explore", """
                你是一个代码库调研子代理。只能使用只读工具（Read/Grep/Glob）。
                目标：快速、有针对性地找到回答任务所需的信息，不要做无关的探索。
                结束时用简洁的中文给出结论：关键文件路径（含行号）、关键发现、仍不确定的点。不要贴大段原文。"""),
        PLAN("plan", """
                你是一个方案设计子代理。只能使用只读工具（Read/Grep/Glob）调研代码。
                结束时给出可执行的实施计划：要改哪些文件、每步做什么、风险与验证方式。不要修改任何文件。"""),
        GENERAL("general", """
                你是一个通用子代理，负责独立完成主代理委派的一个子任务，可以读写文件、执行命令（会经过用户审批）。
                修改文件前必须先 Read。完成后用简洁的中文汇报：做了什么、改了哪些文件、如何验证、遗留问题。""");

        final String id;
        final String systemPrompt;

        Type(String id, String systemPrompt) {
            this.id = id;
            this.systemPrompt = systemPrompt;
        }

        public String id() {
            return id;
        }

        public static Type parse(String s) {
            for (Type t : values()) {
                if (t.id.equalsIgnoreCase(s == null ? "" : s.trim())) {
                    return t;
                }
            }
            return null;
        }
    }

    /** 子代理工具表中永远排除的工具：派生类（深度限制）、交互类（子代理无人对话）、计划态切换。 */
    static final Set<String> EXCLUDED = Set.of(
            "Task", "GeneralTask", "AskUserQuestion", "EnterPlanMode", "ExitPlanMode", "TodoWrite");
    static final Set<String> READ_ONLY_TOOLS = Set.of("Read", "Grep", "Glob");

    public static final int DEFAULT_MAX_STEPS = 25;
    /** 子代理上下文里单个工具结果的上限（子代理没有分层压缩，直接截断）。 */
    static final int MAX_RESULT_CHARS = 30_000;

    private final ModelClient modelClient;
    private final ToolRegistry parentRegistry;
    private final HookChain hookChain;
    private final MccConfig config;
    private final int maxSteps;

    public SubAgentRunner(ModelClient modelClient, ToolRegistry parentRegistry, HookChain hookChain,
                          MccConfig config, int maxSteps) {
        this.modelClient = modelClient;
        this.parentRegistry = parentRegistry;
        this.hookChain = hookChain;
        this.config = config;
        this.maxSteps = maxSteps;
    }

    /** 子代理运行结果（供 Task 工具拼装观察）。 */
    public record Outcome(String text, int steps, int toolCalls, boolean completed) {
    }

    /** 每次运行时按类型过滤父工具表（运行时过滤，MCP 重连后的新工具也能体现）。 */
    ToolRegistry registryFor(Type type) {
        Predicate<Tool> allowed = type == Type.GENERAL
                ? t -> !EXCLUDED.contains(t.name())
                : t -> READ_ONLY_TOOLS.contains(t.name());
        ToolRegistry r = new ToolRegistry();
        parentRegistry.all().stream().filter(allowed).forEach(r::register);
        return r;
    }

    public Outcome run(Type type, String prompt, ToolContext parent) {
        ToolRegistry registry = registryFor(type);
        ToolScheduler scheduler = new ToolScheduler(registry, hookChain);
        ToolContext ctx = new ToolContext(
                parent.workingDir(),
                parent.workspace() == null ? null : parent.workspace().copy(),
                new TodoList(),
                parent.sandbox(),
                parent.bashTimeoutMs(),
                parent.cancelToken(),
                parent.permissions(),      // General 继承“本会话已放行”，用户不必重复批准
                parent.planMode(),
                parent.memoryDir(),
                false,
                parent.checkpoint());      // 写入记到主会话当前轮次

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(type.systemPrompt + "\n\n工作目录：" + parent.workingDir()));
        messages.add(new UserMessage(prompt));
        int toolCalls = 0;
        for (int step = 1; step <= maxSteps; step++) {
            if (parent.cancelToken() != null && parent.cancelToken().isCancelled()) {
                return new Outcome("子代理已被用户中断。", step - 1, toolCalls, false);
            }
            AssistantMessage assistant = modelClient.call(messages, registry.all());
            messages.add(assistant);
            List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
            if (calls == null || calls.isEmpty()) {
                String text = assistant.getText();
                return new Outcome(text == null || text.isBlank() ? "(子代理没有给出结论)" : text, step, toolCalls, true);
            }
            List<ToolResult> results = scheduler.execute(calls, ctx);
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (int k = 0; k < calls.size(); k++) {
                AssistantMessage.ToolCall call = calls.get(k);
                toolCalls++;
                if ("Bash".equals(call.name())) {
                    ctx.checkpoint().noteBash(ToolCallHelper.getString(call, "command", ""));
                }
                String content = truncate(results.get(k).content(),
                        registry.find(call.name()).map(Tool::maxResultChars).orElse(MAX_RESULT_CHARS));
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), content));
            }
            messages.add(ToolResponseMessage.builder().responses(responses).build());
        }
        return new Outcome("子代理达到步数上限（" + maxSteps + "）仍未完成；以下是最后的进展：\n"
                + lastText(messages), maxSteps, toolCalls, false);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (max <= 0 || max > MAX_RESULT_CHARS) {   // 工具声明“不限”（如 Read 自行分页）或过大时，用子代理上限
            max = MAX_RESULT_CHARS;
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n…（已截断，共 " + s.length() + " 字符）";
    }

    private static String lastText(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage a && a.getText() != null && !a.getText().isBlank()) {
                return a.getText();
            }
        }
        return "(无)";
    }
}
