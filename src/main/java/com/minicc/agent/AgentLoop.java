package com.minicc.agent;

import com.minicc.cli.Renderer;
import com.minicc.config.MccConfig;
import com.minicc.compact.CompactionManager;
import com.minicc.context.ContextAssembler;
import com.minicc.memory.MemoryExtractor;
import com.minicc.checkpoint.FileCheckpoint;
import com.minicc.model.ModelCancelledException;
import com.minicc.model.ModelClient;
import com.minicc.model.StreamListener;
import com.minicc.persistence.SessionStore;
import com.minicc.session.Session;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolRegistry;
import com.minicc.tool.ToolResult;
import com.minicc.tool.builtin.TodoWriteTool;
import com.minicc.tool.hook.HookChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 控制循环（harness 核心）：驱动"决策—行动—观察"。
 *
 * <p>每一轮请求模型，若模型要求调用工具就执行并把结果作为观察回填，
 * 直到模型给出不含工具调用的最终答复。
 *
 * <p>M2 的变化：一轮里的多个工具调用不再顺序逐个执行，而是交给 {@link ToolScheduler}
 * 做"只读并行、写操作串行"的调度；结果按原始顺序回填。控制循环的其余结构保持 M1 不变。
 *
 * <p>M5 的变化（都很小）：① 工具执行上下文带上记忆目录 {@code memoryDir}，审批钩子据此自动放行记忆写入；
 * ② 分发工具时顺手记一个布尔「本轮是否写过记忆目录」；③ 一轮收敛（给出不含工具调用的最终答复）后，
 * 若本轮没人主动写过记忆，就调 {@link MemoryExtractor} 跑一次补充提取（两者互斥，避免重复）。
 * 控制循环的骨架不变。
 *
 * <p><b>面试关注：Agent 与普通聊天接口的区别是什么？</b>
 * 模型只负责提出下一步（文本或 tool_calls），本地程序掌握执行权：检查权限、执行工具、
 * 把真实结果作为新消息交回模型。这是一个“决策—行动—观察”的反馈循环，
 * 不是模型输出了“已修改文件”就真的发生了修改，也不是预先固定好每一步的工作流。
 *
 * <p><b>面试关注：本类维护哪些不变量？</b>
 * <ul>
 *   <li>先记录包含 tool_calls 的助手消息，再按调用顺序追加对应结果；
 *       即使被拒绝或取消，也要返回观察，避免下一次请求含有悬空的调用。</li>
 *   <li>工具错误通常作为观察交给模型修正；模型调用失败则结束本轮，保留已经产生的历史与副作用。</li>
 *   <li>一次用户输入可以包含多次模型往返；maxSteps 限制这里的循环次数，
 *       不是工具总数，也不涵盖压缩、记忆提取的额外模型调用。</li>
 * </ul>
 *
 * <p><b>面试追问：停止是否等于任务成功？</b>
 * 不含工具调用只代表模型决定结束，代码没有额外验证“用户目标已完成”。取消和步数限制也不会回滚
 * 已写文件。本实现同步等待模型，取消标志不会直接中断正在进行的网络请求；如需更及时的停止，
 * 还需要模型客户端支持请求取消与超时。上述调用/结果配对是正常控制流的约束，
 * 不是跨崩溃的事务保证，进程中途退出仍需恢复逻辑修补。
 */
public class AgentLoop {

    private final ModelClient modelClient;
    private final ContextAssembler assembler;
    private final ToolRegistry registry;
    private final Renderer renderer;
    private final MccConfig config;
    private final ToolScheduler scheduler;
    private final CancelToken cancelToken;
    private final Path memoryDir;                 // M5：记忆目录（可为 null，表示未启用记忆）
    private final MemoryExtractor memoryExtractor; // M5：轮末补充提取（可为 null，表示不做提取）
    private final CompactionManager compactionManager; // M6：工具结果入库前的第①层落盘（可为 null）

    public AgentLoop(ModelClient modelClient,
                     ContextAssembler assembler,
                     ToolRegistry registry,
                     HookChain hookChain,
                     Renderer renderer,
                     MccConfig config,
                     CancelToken cancelToken) {
        this(modelClient, assembler, registry, hookChain, renderer, config, cancelToken, null, null, null);
    }

    public AgentLoop(ModelClient modelClient,
                     ContextAssembler assembler,
                     ToolRegistry registry,
                     HookChain hookChain,
                     Renderer renderer,
                     MccConfig config,
                     CancelToken cancelToken,
                     Path memoryDir,
                     MemoryExtractor memoryExtractor,
                     CompactionManager compactionManager) {
        this.modelClient = modelClient;
        this.assembler = assembler;
        this.registry = registry;
        this.renderer = renderer;
        this.config = config;
        this.cancelToken = cancelToken;
        this.memoryDir = memoryDir;
        this.memoryExtractor = memoryExtractor;
        this.compactionManager = compactionManager;
        // 调度器承接 M1 分发循环里"找工具 + 过钩子 + 执行"的职责，并加上并行/串行调度。
        this.scheduler = new ToolScheduler(registry, hookChain);
    }

    /** 处理用户的一条输入：驱动循环直到给出最终答复或触发边界。 */
    public RunResult run(Session session) {
        // 工具执行上下文：M2 带上工作区状态（改前先读）、待办清单、沙箱与超时配置；
        // M3 再带上取消信号与会话级放行状态（后者由 Session 持有、这里下发给审批钩子）；
        // M8 再带上会话级计划态（由 Session 持有、这里下发给 PlanModeHook，计划态下拦住写操作）。
        ToolContext toolContext = new ToolContext(
                session.workingDir(),
                session.workspace(),
                session.todos(),
                config.sandbox(),
                config.bashTimeoutMs(),
                cancelToken,
                session.permissions(),
                session.planMode(),   // M8：计划态开关，PlanModeHook 据此拦住写操作
                memoryDir,            // M5：记忆目录，审批钩子据此自动放行记忆写入
                false,
                // M10：本轮的文件检查点（记忆目录的写入不纳入回退）
                FileCheckpoint.of(session.checkpoints(), session.currentTurnId(), memoryDir));
        session.checkpoints().registerTurn(session.currentTurnId());
        int step = 0;
        int toolCalls = 0;   // M0：结构化结果统计
        int toolErrors = 0;

        // M5：本轮开始时的历史位置（含刚追加的用户消息），用于收尾时截取「本轮对话」交给提取。
        int turnStart = Math.max(0, session.history().size() - 1);
        // M5：本轮是否写过记忆目录。写过 → 轮末不再重复提取（主动保存与兜底提取互斥）。
        boolean wroteMemoryThisTurn = false;

        while (true) {
            // 检查点①：循环顶部查取消信号（Ctrl-C）。命中则安全停下、保留会话状态。
            if (isCancelled()) {
                renderer.interrupted();
                return new RunResult(RunResult.Status.CANCELLED, null, step, toolCalls, toolErrors);
            }

            // 边界控制：超过最大步数就停下，防止无限循环。
            if (step++ >= config.maxSteps()) {
                renderer.warn("已达到最大步数上限（" + config.maxSteps() + "），停止本轮任务。");
                return new RunResult(RunResult.Status.MAX_STEPS, null, step - 1, toolCalls, toolErrors);
            }

            // 1) 拼好本次上下文，请求模型，再把这一轮原样写回会话。
            //    要派发的工具调用就是这条助手消息上的 AssistantMessage.ToolCall。
            AssistantMessage assistantMessage;
            boolean streamed = config.stream();
            try {
                // 每一步重新组装，而不是只在用户输入时组装：工具结果、计划态、记忆和压缩状态都可能已变化。
                List<Message> messages = assembler.assemble(session);
                if (streamed) {
                    // M9：流式——增量直接上屏；完整消息在流结束后才返回，再一次性写入会话。
                    assistantMessage = modelClient.stream(messages, registry.all(), cancelToken, streamListener());
                    renderer.streamEnd();
                } else {
                    assistantMessage = modelClient.call(messages, registry.all());
                }
                session.appendAssistant(assistantMessage);
            } catch (ModelCancelledException e) {
                // M9：请求中途被取消——半截内容只上过屏，不进历史；会话停在上一条完整消息。
                renderer.streamEnd();
                renderer.interrupted();
                return new RunResult(RunResult.Status.CANCELLED, null, step, toolCalls, toolErrors);
            } catch (Exception e) {
                if (streamed) {
                    renderer.streamEnd();
                }
                renderer.error("调用模型失败：" + e.getMessage());
                return new RunResult(RunResult.Status.ERROR, "调用模型失败：" + e.getMessage(), step, toolCalls, toolErrors);
            }
            List<AssistantMessage.ToolCall> calls = assistantMessage.getToolCalls();
            if (calls == null) {
                calls = List.of();
            }

            // 检查点②：模型返回后、派发工具前再查一次取消。
            //   若本轮不含工具调用，直接停下即可（历史合法）。若含工具调用，则不在此处 return——
            //   交给调度器：它发现已取消后会把这批调用全部标记为"未执行"并回填，保证助手的 tool_calls
            //   都有对应的工具响应，取消后继续对话时请求体才完整。随后由检查点①停下。
            if (isCancelled() && calls.isEmpty()) {
                renderer.interrupted();
                return new RunResult(RunResult.Status.CANCELLED, null, step, toolCalls, toolErrors);
            }

            // 2) 没有工具调用 → 这是最终答复，渲染后结束。
            if (calls.isEmpty()) {
                if (streamed) {
                    renderer.info("");   // 流式下正文已上屏，只补一行空白与过程区隔开
                } else {
                    renderReasoning(assistantMessage);
                    renderer.finalAnswer(assistantMessage.getText());
                }
                // M5：一轮收敛后补做一次提取兜底——但本轮若已主动写过记忆，则跳过（互斥，避免重复）。
                maybeExtractMemories(session, turnStart, wroteMemoryThisTurn);
                return new RunResult(RunResult.Status.COMPLETED, assistantMessage.getText(), step, toolCalls, toolErrors);
            }

            // 思维链在正文和工具调用之前，调暗显示，避免和最终答复混在一起。
            if (!streamed) {
                renderReasoning(assistantMessage);
            }
            // 若本轮既有文本又有工具调用，先把文本展示出来（模型常用来说明接下来要做什么）。流式下已上屏。
            if (!streamed && assistantMessage.getText() != null && !assistantMessage.getText().isBlank()) {
                renderer.thought(assistantMessage.getText());
            }

            // 3) 有工具调用 → 交给调度器执行，再按原始顺序成对渲染"调用 + 结果"并回填观察。
            //    并行执行时若先把所有 ⏺ 调用一次性列出、再统一列结果，call 与 result 在屏幕上
            //    对不上号；这里改为拿到结果后逐个输出 call+result，保证一一对应、便于阅读。
            List<ToolResult> results = scheduler.execute(calls, toolContext);
            for (int k = 0; k < calls.size(); k++) {
                AssistantMessage.ToolCall call = calls.get(k);
                ToolResult result = results.get(k);
                toolCalls++;
                if ("Bash".equals(call.name())) {
                    // M10：Bash 的文件改动无法追踪，记一笔，回退时提示“文件回退可能不完整”。
                    session.checkpoints().noteBash(session.currentTurnId(),
                            ToolCallHelper.getString(call, "command", ""));
                }
                if (result.error()) {
                    toolErrors++;
                }
                renderer.toolUse(call);
                renderer.toolResult(result);
                // M6 第①层：工具结果写入内存历史/日志之前，先过一遍"结果预算"——
                // 超阈值的大结果当场落盘到 tool-results/，要入库的那条换成"预览+路径"（产生时即定稿）。
                ToolResponseMessage toolResponseMessage = Session.toToolResponse(call, result);
                if (compactionManager != null) {
                    toolResponseMessage = compactionManager.offloadToolResult(toolResponseMessage);
                }
                session.appendToolResult(toolResponseMessage); // 回填为观察（大结果已是预览+路径，完整原文在 tool-results/）
                // 清单只回填给模型一句短回执，完整的勾选清单在这里单独渲染给人看。
                if (TodoWriteTool.NAME.equals(call.name()) && !result.error()) {
                    renderer.todoList(session.todos().items());
                    session.writeTodoSnapshot(); // M4：待办更新后整份覆盖写一次最新态快照
                }
                // M5：这次写入落在记忆目录内且成功 → 主 Agent 主动记过了，标记本轮已写、给人一行回执。
                if (isMemoryWrite(call, result)) {
                    wroteMemoryThisTurn = true;
                    renderer.memorySaved(fileName(call));
                }
            }
            // 回到循环顶部，带着新观察再次请求模型（下一轮顶部的检查点①会捕获执行期间发生的取消）。
        }
    }

    /** M9：把流式增量接到终端渲染器。 */
    private StreamListener streamListener() {
        return new StreamListener() {
            @Override
            public void onReasoningDelta(String delta) {
                renderer.streamReasoning(delta);
            }

            @Override
            public void onTextDelta(String delta) {
                renderer.streamText(delta);
            }

            @Override
            public void onRetry(int attempt, long delayMs, String reason) {
                renderer.retrying(attempt, delayMs, reason);
            }
        };
    }

    /** 把思考模式的思维链打到终端。没有则跳过。 */
    private void renderReasoning(AssistantMessage message) {
        Object reasoning = message.getMetadata().get(SessionStore.REASONING_CONTENT);
        if (reasoning instanceof String text) {
            renderer.reasoning(text);
        }
    }

    /** 是否已被取消（cancelToken 可能为 null，保持对不带取消场景的兼容）。 */
    private boolean isCancelled() {
        return cancelToken != null && cancelToken.isCancelled();
    }

    /**
     * 轮末补充提取（M5）：本轮没人主动写过记忆、且配置了提取器时，回看本轮对话补一次提取。
     * 取消时不提取（任务被打断，不是正常收敛）。提取产物不进会话历史、不落盘。
     */
    private void maybeExtractMemories(Session session, int turnStart, boolean wroteMemoryThisTurn) {
        if (memoryExtractor == null || wroteMemoryThisTurn || isCancelled()) {
            return;
        }
        List<Message> history = session.history();
        int end = history.size();
        if (turnStart >= end) {
            return;
        }
        // 复制出「本轮对话」交给提取（用户请求 + 中间助手/工具消息 + 最终答复）。
        List<Message> turnMessages = new ArrayList<>(history.subList(turnStart, end));
        memoryExtractor.extract(session, turnMessages);
    }

    /** 这次调用是否「成功写入记忆目录」：是 Edit/Write、无错误、且目标落在记忆目录内。 */
    private boolean isMemoryWrite(AssistantMessage.ToolCall call, ToolResult result) {
        if (memoryDir == null || result.error() || !isWriteTool(call.name())) {
            return false;
        }
        String filePath = ToolCallHelper.getString(call, "file_path", "").trim();
        if (filePath.isEmpty()) {
            return false;
        }
        Path target = Path.of(filePath);
        if (!target.isAbsolute()) {
            return false;
        }
        return target.normalize().startsWith(memoryDir.toAbsolutePath().normalize());
    }

    /** 从写操作调用里取文件名（用于「已保存记忆：xxx.md」的回执）。 */
    private static String fileName(AssistantMessage.ToolCall call) {
        String filePath = ToolCallHelper.getString(call, "file_path", "").trim();
        if (filePath.isEmpty()) {
            return "(记忆文件)";
        }
        return Path.of(filePath).getFileName().toString();
    }

    /** 是否文件写入工具（Edit/Write）。 */
    private static boolean isWriteTool(String name) {
        return "Edit".equals(name) || "Write".equals(name);
    }
}
