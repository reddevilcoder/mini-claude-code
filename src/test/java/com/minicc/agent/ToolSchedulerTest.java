package com.minicc.agent;

import com.minicc.permission.PermissionState;
import com.minicc.session.TodoList;
import com.minicc.session.WorkspaceState;
import com.minicc.tool.Schemas;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolRegistry;
import com.minicc.tool.ToolResult;
import com.minicc.tool.hook.HookChain;
import com.minicc.tool.hook.PreExecuteHook;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ToolScheduler 的单元测试：验证顺序保持、只读并行、写操作串行。 */
class ToolSchedulerTest {

    private static ToolContext ctx() {
        return ctx(new CancelToken());
    }

    private static ToolContext ctx(CancelToken token) {
        return new ToolContext(Path.of("."), new WorkspaceState(), new TodoList(), false, 0,
                token, new PermissionState());
    }

    private static AssistantMessage.ToolCall call(String name) {
        return ToolCallHelper.of("id-" + name, name, Map.of());
    }

    /** 一个可配置并发安全性、执行时 sleep 并回显名字的假工具。 */
    private static class FakeTool implements Tool {
        private final String name;
        private final boolean safe;
        private final long sleepMs;
        private final AtomicInteger concurrent; // 记录并发峰值的共享计数器
        private final AtomicInteger peak;

        FakeTool(String name, boolean safe, long sleepMs, AtomicInteger concurrent, AtomicInteger peak) {
            this.name = name;
            this.safe = safe;
            this.sleepMs = sleepMs;
            this.concurrent = concurrent;
            this.peak = peak;
        }

        @Override public String name() { return name; }
        @Override public String description() { return name; }
        @Override public Map<String, Object> paramsSchema() { return Schemas.object(Map.of(), List.of()); }
        @Override public boolean isReadOnly() { return safe; }
        @Override public boolean isConcurrencySafe() { return safe; }

        @Override
        public ToolResult execute(AssistantMessage.ToolCall c, ToolContext x) {
            int now = concurrent.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
            return ToolResult.ok(name);
        }
    }

    @Test
    void preservesOriginalOrder() {
        AtomicInteger c = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("A", true, 0, c, peak));
        registry.register(new FakeTool("B", false, 0, c, peak));
        registry.register(new FakeTool("C", true, 0, c, peak));
        ToolScheduler scheduler = new ToolScheduler(registry, new HookChain(List.of()));

        List<ToolResult> results = scheduler.execute(
                List.of(call("A"), call("B"), call("C")), ctx());

        assertEquals(List.of("A", "B", "C"),
                results.stream().map(ToolResult::content).toList());
    }

    @Test
    void readOnlyToolsRunInParallel() {
        AtomicInteger c = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("A", true, 200, c, peak));
        registry.register(new FakeTool("B", true, 200, c, peak));
        ToolScheduler scheduler = new ToolScheduler(registry, new HookChain(List.of()));

        scheduler.execute(List.of(call("A"), call("B")), ctx());
        assertTrue(peak.get() >= 2, "两个只读工具应并行执行，峰值并发应 >= 2，实际=" + peak.get());
    }

    @Test
    void writeToolsRunSerially() {
        AtomicInteger c = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("W1", false, 100, c, peak));
        registry.register(new FakeTool("W2", false, 100, c, peak));
        ToolScheduler scheduler = new ToolScheduler(registry, new HookChain(List.of()));

        scheduler.execute(List.of(call("W1"), call("W2")), ctx());
        assertEquals(1, peak.get(), "写操作应串行，峰值并发应为 1");
    }

    @Test
    void unknownToolReturnsErrorInOrder() {
        AtomicInteger c = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("A", true, 0, c, peak));
        ToolScheduler scheduler = new ToolScheduler(registry, new HookChain(List.of()));

        List<ToolResult> results = scheduler.execute(
                List.of(call("A"), call("NoSuch")), ctx());

        assertEquals("A", results.get(0).content());
        assertTrue(results.get(1).error());
        assertTrue(results.get(1).content().contains("未知工具"));
    }

    @Test
    void cancelledTokenSkipsExecution() {
        AtomicInteger c = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("W1", false, 0, c, peak));
        registry.register(new FakeTool("W2", false, 0, c, peak));
        ToolScheduler scheduler = new ToolScheduler(registry, new HookChain(List.of()));

        CancelToken token = new CancelToken();
        token.cancel(); // 开始前就已取消

        List<ToolResult> results = scheduler.execute(List.of(call("W1"), call("W2")), ctx(token));

        // 两个调用都未执行（peak 并发为 0），且都被填成"未执行"的结果，保持结果与调用一一对应。
        assertEquals(0, peak.get(), "已取消时不应派发任何工具");
        assertEquals(2, results.size());
        assertTrue(results.get(0).content().contains("中断"));
        assertTrue(results.get(1).content().contains("中断"));
    }

    @Test
    void deniedHookReturnsReason() {
        AtomicInteger c = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("W", false, 0, c, peak));
        // 一个总是拒绝并带原因的钩子。
        HookChain chain = new HookChain(List.of(
                (call, tool, ctx) -> PreExecuteHook.Decision.deny("先别动这个文件")));
        ToolScheduler scheduler = new ToolScheduler(registry, chain);

        List<ToolResult> results = scheduler.execute(List.of(call("W")), ctx());

        assertEquals(0, peak.get(), "被拒绝的工具不应执行");
        assertTrue(results.get(0).error());
        assertTrue(results.get(0).content().contains("先别动这个文件"), "拒绝原因应回填给模型");
    }
}
