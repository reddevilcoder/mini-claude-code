package com.minicc.agent;

import com.minicc.tool.Tool;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolRegistry;
import com.minicc.tool.ToolResult;
import com.minicc.tool.hook.HookChain;
import com.minicc.tool.hook.PreExecuteHook;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 工具调度器：只读并行、写操作串行。
 *
 * <p>并发处理策略——并发执行 isConcurrencySafe（只读）工具、
 * 串行执行有副作用的工具。M2 的策略是：保持模型给出的调用顺序，把<b>连续的</b> isConcurrencySafe
 * 调用攒成一批并行执行；遇到不可并行的调用，先等前面这批跑完，再单独串行执行它；
 * 结果始终<b>按原始顺序</b>回填为观察，保证模型看到的上下文顺序稳定。
 *
 * <p>钩子检查（{@link HookChain}）在执行每个工具之前进行（M1 空链、M2 仍为空，审批在 M3 挂上）。
 *
 * <p><b>面试关注：为什么只并行“连续的一段”？</b>
 * 例如 Read(A)、Read(B)、Edit(A)、Read(A)，前两个读取可以并行，Edit 必须等它们结束，
 * 最后一次 Read 必须等 Edit 完成。写操作在这里形成一道屏障；若把所有只读调用统一提前并行，
 * 最后一次读取可能读到修改前的内容，改变模型原本给出的执行顺序。
 *
 * <p><b>面试关注：执行顺序和回填顺序如何解耦？</b>
 * 每个任务只写 results 中属于自己的下标，完成先后可以不同，返回顺序仍与 calls 相同。
 * 正常等待时 Future.get() 提供完成同步和内存可见性，不需要给每次数组写入加同一把锁。
 * 并发安全依据工具的 isConcurrencySafe 声明，不会自动分析文件依赖；共享上下文本身也必须支持相应访问。
 *
 * <p><b>面试追问：生产环境还需要什么？</b>
 * cachedThreadPool 没有固定线程数上限，守护线程也不等于资源已正确关闭；
 * 可按需求补充并发限流、队列背压、任务超时与显式生命周期管理。
 * 当前调度仅约束这个调用批次，不提供跨进程文件锁、事务回滚、失败后的自动重试或“恰好执行一次”保证。
 */
public class ToolScheduler {

    private final ToolRegistry registry;
    private final HookChain hookChain;
    private final ExecutorService pool;

    public ToolScheduler(ToolRegistry registry, HookChain hookChain) {
        this.registry = registry;
        this.hookChain = hookChain;
        // 守护线程池：供只读工具并行；用守护线程，JVM 退出时不被阻塞。
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mcc-tool");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 执行一轮里的所有工具调用，结果按原始顺序返回。
     *
     * <p>M3：在开始执行一轮前、以及串行执行每个工具之间查询取消信号；一旦取消就<b>不再派发后续工具</b>，
     * 把剩余调用填成"已取消/未执行"的观察（保持结果数组与 calls 一一对应，也让会话历史合法，
     * 便于取消后继续对话）。正在并行跑的只读工具很快结束，不强杀。
     *
     * @param calls 模型本轮要求的工具调用（保持其顺序）
     * @param ctx   工具执行上下文
     */
    public List<ToolResult> execute(List<AssistantMessage.ToolCall> calls, ToolContext ctx) {
        ToolResult[] results = new ToolResult[calls.size()];
        int i = 0;
        while (i < calls.size()) {
            // 取消检查点：已取消则不再派发，剩余调用标记为"未执行"。
            if (isCancelled(ctx)) {
                break;
            }
            if (isConcurrencySafe(calls.get(i))) {
                // 攒一批连续的可并行调用，一起提交线程池。
                List<Future<?>> futures = new ArrayList<>();
                int j = i;
                while (j < calls.size() && isConcurrencySafe(calls.get(j))) {
                    // 捕获不可变下标，避免闭包错误地引用持续变化的循环变量；数组槽位就是原始调用的位置。
                    final int idx = j;
                    futures.add(pool.submit(() -> results[idx] = runOne(calls.get(idx), ctx)));
                    j++;
                }
                awaitAll(futures);
                i = j;
            } else {
                // 串行执行一个有副作用的调用。
                results[i] = runOne(calls.get(i), ctx);
                i++;
            }
        }
        // 把因取消而未派发的尾部调用填成"已取消"，保证结果与 calls 一一对应。
        for (int k = 0; k < results.length; k++) {
            if (results[k] == null) {
                results[k] = ToolResult.cancelled();
            }
        }
        return List.of(results);
    }

    /** 当前上下文是否已被取消（cancelToken 可能为 null，兼容不带取消的调用方）。 */
    private static boolean isCancelled(ToolContext ctx) {
        return ctx.cancelToken() != null && ctx.cancelToken().isCancelled();
    }

    /** 该调用对应的工具是否可并行（未知工具按不可并行处理，走串行分支以便回填错误）。 */
    private boolean isConcurrencySafe(AssistantMessage.ToolCall call) {
        return registry.find(call.name()).map(Tool::isConcurrencySafe).orElse(false);
    }

    /** 执行单个工具调用：未知工具 / 钩子拒绝 / 正常执行，分别回填对应结果。 */
    private ToolResult runOne(AssistantMessage.ToolCall call, ToolContext ctx) {
        Tool tool = registry.find(call.name()).orElse(null);
        if (tool == null) {
            // 未知工具不中断循环，回填错误观察，让模型改用正确的工具。
            return ToolResult.error("未知工具：" + call.name());
        }
        // 执行前钩子（M1/M2 空链，全部放行；M3 的审批、M6 的 Plan Mode 挂在这里）。
        PreExecuteHook.Decision decision = hookChain.beforeExecute(call, tool, ctx);
        if (!decision.allowed()) {
            return ToolResult.denied(call, decision.reason()); // 带回拒绝原因回填给模型
        }
        return safeExecute(tool, call, ctx);
    }

    /** 执行工具并捕获异常，保证并行批里单个工具崩溃不影响其它工具与整个循环。 */
    private ToolResult safeExecute(Tool tool, AssistantMessage.ToolCall call, ToolContext ctx) {
        try {
            return tool.execute(call, ctx);
        } catch (Exception e) {
            return ToolResult.error("工具执行失败：" + e.getMessage());
        }
    }

    /** 等待一批并行任务全部完成。 */
    private static void awaitAll(List<Future<?>> futures) {
        for (Future<?> f : futures) {
            try {
                // 任务已全部提交，按顺序 get 只决定“怎样等结果”，不会把并行执行退化成逐个执行。
                f.get();
            } catch (Exception e) {
                // execute 的普通异常在 safeExecute 中转成结果；钩子抛异常、任务 Error 或等待被中断仍可能到这里。
                // 当前只恢复中断标记并继续，未填充的结果最终归为 cancelled；因此不是精确的失败分类。
                // 尤其等待线程被中断时，并不保证所有任务已结束，不能再把它视为完整的批次屏障。
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
