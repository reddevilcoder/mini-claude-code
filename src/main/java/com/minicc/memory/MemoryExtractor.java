package com.minicc.memory;

import com.minicc.agent.ToolScheduler;
import com.minicc.cli.Renderer;
import com.minicc.config.MccConfig;
import com.minicc.model.ModelClient;
import com.minicc.permission.PermissionState;
import com.minicc.session.Session;
import com.minicc.session.TodoList;
import com.minicc.session.WorkspaceState;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolRegistry;
import com.minicc.tool.ToolResult;
import com.minicc.tool.hook.HookChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 每轮结束的补充提取（兜底）：一轮任务收敛（模型给出不含工具调用的最终答复）后，回看本轮对话，
 * 把「该记而没记」的长期记忆补写进记忆目录。
 *
 * <p>为什么要这一步：主 Agent 在专注完成编码任务时，很可能没顾上把「用户刚纠正了我」这类信息存进记忆。
 * 补充提取是一道兜底——专门回看这一轮对话，把漏记的补上。它与主 Agent 的「主动保存」<b>互斥</b>：
 * 本轮主 Agent 已经写过记忆目录，就直接跳过提取（互斥判断在 {@code AgentLoop} 里，用一个布尔实现）。
 *
 * <p>实现方式：理想做法是跑在一个共享主对话前缀缓存的 forked agent（子 Agent）里；mini-cc 的
 * 子 Agent 抽象要到 M7，所以这里用一个<b>受限的一次性提取回合</b>——复用控制循环的「调模型 + 调度工具」，但：
 * <ol>
 *   <li><b>输入</b>：本轮的对话消息 + 一条「提取提示词」用户消息（含四类型/什么不该存/两步保存 + 现有 {@code MEMORY.md}）；</li>
 *   <li><b>工具</b>：只给 Read/Grep/Glob 和「仅限记忆目录的 Write/Edit」（靠 {@link ToolContext#memoryWritesOnly()}
 *       判断，和主循环同一套审批规则）；</li>
 *   <li><b>边界</b>：很小的步数上限（默认 5）；</li>
 *   <li><b>隔离</b>：提取回合的消息<b>不写进主会话历史、不落盘</b>，唯一产出就是记忆目录里新增/更新的文件。</li>
 * </ol>
 */
public class MemoryExtractor {

    private final ModelClient modelClient;
    private final ToolRegistry memoryRegistry;   // 只含 Read/Grep/Glob/Edit/Write
    private final ToolScheduler scheduler;       // 复用控制循环的「找工具 + 过钩子 + 执行」
    private final AutoMemory autoMemory;
    private final MccConfig config;
    private final Renderer renderer;
    private final int maxSteps;

    public MemoryExtractor(ModelClient modelClient,
                           ToolRegistry memoryRegistry,
                           HookChain hookChain,
                           AutoMemory autoMemory,
                           MccConfig config,
                           Renderer renderer) {
        this.modelClient = modelClient;
        this.memoryRegistry = memoryRegistry;
        this.scheduler = new ToolScheduler(memoryRegistry, hookChain);
        this.autoMemory = autoMemory;
        this.config = config;
        this.renderer = renderer;
        this.maxSteps = 5;   // 良好的提取 2–4 个回合就完成（读相关文件 → 写），给点余量
    }

    /**
     * 跑一次受限的提取回合。调用方（{@code AgentLoop}）已做过互斥判断：本轮没人主动写过记忆才会调到这里。
     *
     * @param session      当前会话（取工作目录；提取产物不写回它的历史、不落盘）
     * @param turnMessages 本轮的对话消息（用户请求 + 中间的助手/工具消息 + 最终答复）
     */
    public void extract(Session session, List<Message> turnMessages) {
        if (turnMessages == null || turnMessages.isEmpty()) {
            return;
        }
        try {
            String index = autoMemory.readMemoryIndex().orElse("");
            int visibleCount = countModelVisible(turnMessages);
            String prompt = MemoryPromptText.extractionPrompt(visibleCount, index);

            // 输入 = 本轮对话 + 一条提取提示词用户消息（提示词里说「分析上面这 ~N 条消息」）。
            List<Message> input = new ArrayList<>(turnMessages);
            input.add(new UserMessage(prompt));

            // 记忆专用上下文：换一套干净工作区（供提取内部的 Read→Edit 校验），写入只允许落在记忆目录。
            ToolContext memCtx = new ToolContext(
                    session.workingDir(),
                    new WorkspaceState(),
                    new TodoList(),
                    config.sandbox(),
                    config.bashTimeoutMs(),
                    null,                               // 提取很短，无需取消信号
                    new PermissionState(),              // 不复用会话放行；提取靠 memoryWritesOnly 约束
                    autoMemory.dir(),
                    true);                              // memoryWritesOnly：只放行记忆目录内的写

            // 提取是同步的模型调用（最多 5 步），会占住几秒、期间 Repl 还没回到 "> "。
            // 先打一行提示，让这段静默期对用户可见（否则会被当成卡死）。
            if (renderer != null) {
                renderer.memoryExtractionStart();
            }

            boolean wroteSomething = runBoundedLoop(input, memCtx);
            if (wroteSomething && renderer != null) {
                renderer.memoryNote("已从本轮对话补充记忆。");
            }
            // 收尾行：与开头的「正在整理记忆…」成对，明确告诉用户这段静默期已经结束。
            // 只有正常跑完才打；中途异常走下面的「跳过」分支，不再声称"整理完成"。
            if (renderer != null) {
                renderer.memoryExtractionEnd();
            }
        } catch (Exception e) {
            // 提取是旁路，任何失败都降级为「这次没提取」，绝不打断主流程。
            if (renderer != null) {
                renderer.memoryNote("记忆提取跳过（" + e.getMessage() + "）。");
            }
        }
    }

    /**
     * 受限的一次性循环：最多 {@link #maxSteps} 步，复用调度器执行工具；产物只在记忆目录里。
     * 返回本次是否至少成功写过一次记忆文件（供渲染一句提示）。
     */
    private boolean runBoundedLoop(List<Message> messages, ToolContext memCtx) {
        boolean wrote = false;
        for (int step = 0; step < maxSteps; step++) {
            AssistantMessage assistant = modelClient.call(messages, memoryRegistry.all());
            messages.add(assistant);
            List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
            if (calls == null || calls.isEmpty()) {
                break;   // 提取回合给出不含工具调用的收尾 → 结束
            }
            List<ToolResult> results = scheduler.execute(calls, memCtx);
            for (int k = 0; k < calls.size(); k++) {
                AssistantMessage.ToolCall call = calls.get(k);
                ToolResult result = results.get(k);
                if (isWriteTool(call.name()) && !result.error()) {
                    wrote = true;
                }
                // 工具结果回填进提取回合自己的消息列表（不进主会话历史）。
                messages.add(ToolResponseMessage.builder()
                        .responses(List.of(
                                new ToolResponseMessage.ToolResponse(call.id(), call.name(), result.content())))
                        .build());
            }
        }
        return wrote;
    }

    /** 统计本轮里模型可见的消息条数（用户 + 助手），作为提示词里的 ~N。 */
    private static int countModelVisible(List<Message> messages) {
        int n = 0;
        for (Message m : messages) {
            if (m instanceof UserMessage || m instanceof AssistantMessage) {
                n++;
            }
        }
        return n;
    }

    /** 是否文件写入工具（记忆通过 Edit/Write 落盘）。 */
    private static boolean isWriteTool(String name) {
        return "Edit".equals(name) || "Write".equals(name);
    }
}
