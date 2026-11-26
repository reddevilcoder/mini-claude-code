package com.minicc.agent;

import com.minicc.checkpoint.CheckpointStore;
import com.minicc.checkpoint.FileCheckpoint;
import com.minicc.config.MccConfig;
import com.minicc.model.ModelClient;
import com.minicc.permission.Approval;
import com.minicc.permission.ApprovalHook;
import com.minicc.permission.PermissionState;
import com.minicc.plan.PlanModeHook;
import com.minicc.plan.PlanModeState;
import com.minicc.session.TodoList;
import com.minicc.session.WorkspaceState;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolRegistry;
import com.minicc.tool.ToolResult;
import com.minicc.tool.builtin.BashTool;
import com.minicc.tool.builtin.GlobTool;
import com.minicc.tool.builtin.ReadTool;
import com.minicc.tool.builtin.TaskTool;
import com.minicc.tool.builtin.WriteTool;
import com.minicc.tool.hook.HookChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentRunnerTest {

    /** 按脚本回放；同时记下每次请求里带了哪些工具。 */
    private static final class Scripted implements ChatModel {
        final List<AssistantMessage> script;
        final AtomicInteger n = new AtomicInteger();
        volatile Set<String> lastTools = Set.of();

        Scripted(List<AssistantMessage> script) {
            this.script = script;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            var opts = prompt.getOptions();
            if (opts instanceof org.springframework.ai.model.tool.ToolCallingChatOptions t && t.getToolCallbacks() != null) {
                lastTools = t.getToolCallbacks().stream().map(c -> c.getToolDefinition().name()).collect(Collectors.toSet());
            }
            AssistantMessage next = script.get(Math.min(n.getAndIncrement(), script.size() - 1));
            return new ChatResponse(List.of(new Generation(next)));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }

    private static AssistantMessage toolCall(String name, Map<String, Object> args) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(ToolCallHelper.of("c" + name, name, args))).build();
    }

    private static AssistantMessage text(String t) {
        return AssistantMessage.builder().content(t).build();
    }

    private static MccConfig config() {
        return new MccConfig("fake", 40, 200000, "", "http://localhost", false, 120000, MccConfig.DEFAULT_STATE_DIR);
    }

    private static ToolRegistry parentRegistry() {
        ToolRegistry r = new ToolRegistry();
        r.register(new ReadTool());
        r.register(new GlobTool());
        r.register(new WriteTool());
        r.register(new BashTool());
        return r;
    }

    @Test
    void exploreGetsOnlyReadOnlyToolsAndNoTaskTool(@TempDir Path dir) {
        ToolRegistry parent = parentRegistry();
        SubAgentRunner runner = new SubAgentRunner(new ModelClient(new Scripted(List.of(text("x"))), config()),
                parent, new HookChain(List.of()), config(), 5);
        parent.register(TaskTool.readOnly(runner));
        parent.register(TaskTool.general(runner));

        Set<String> explore = runner.registryFor(SubAgentRunner.Type.EXPLORE).all().stream()
                .map(Tool::name).collect(Collectors.toSet());
        assertEquals(Set.of("Read", "Glob"), explore);
        Set<String> general = runner.registryFor(SubAgentRunner.Type.GENERAL).all().stream()
                .map(Tool::name).collect(Collectors.toSet());
        assertEquals(Set.of("Read", "Glob", "Write", "Bash"), general);   // 深度 1：没有 Task/GeneralTask

        assertTrue(TaskTool.readOnly(runner).isConcurrencySafe());
        assertFalse(TaskTool.general(runner).isReadOnly());
    }

    @Test
    void generalWritesGoThroughApprovalAndCheckpointAndOnlyResultReturns(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("out.txt");
        Scripted model = new Scripted(List.of(
                toolCall("Write", Map.of("file_path", file.toString(), "content", "hello")),
                text("已写入 out.txt")));
        ModelClient client = new ModelClient(model, config());
        AtomicInteger approvals = new AtomicInteger();
        HookChain hooks = new HookChain(List.of(new PlanModeHook(), new ApprovalHook((c, t, x) -> {
            approvals.incrementAndGet();
            return Approval.allowOnce();
        })));
        SubAgentRunner runner = new SubAgentRunner(client, parentRegistry(), hooks, config(), 5);

        CheckpointStore store = new CheckpointStore(dir.resolve("cp"));
        store.registerTurn("t1");
        WorkspaceState parentWs = new WorkspaceState();
        ToolContext ctx = new ToolContext(dir, parentWs, new TodoList(), false, 1000, new CancelToken(),
                new PermissionState(), new PlanModeState(), null, false, FileCheckpoint.of(store, "t1", null));

        ToolResult r = TaskTool.general(runner).execute(
                ToolCallHelper.of("g", TaskTool.GENERAL_NAME, Map.of("description", "写文件", "prompt", "写 out.txt")), ctx);
        assertFalse(r.error(), r.content());
        assertTrue(r.content().contains("已写入 out.txt"));
        assertEquals("hello", Files.readString(file));
        assertEquals(1, approvals.get());
        assertTrue(store.hasChanges("t1"));                    // 写入记到主会话当前轮
        store.restoreFrom("t1", false);
        assertFalse(Files.exists(file));
        assertEquals(2, client.usage().requests());             // 用量累计到共享的 ModelClient
    }

    @Test
    void planModeBlocksGeneralWritesButExploreRuns(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "x");
        Scripted model = new Scripted(List.of(
                toolCall("Write", Map.of("file_path", file.toString(), "content", "y")),
                text("done")));
        HookChain hooks = new HookChain(List.of(new PlanModeHook(), new ApprovalHook((c, t, x) -> Approval.allowOnce())));
        SubAgentRunner runner = new SubAgentRunner(new ModelClient(model, config()), parentRegistry(), hooks, config(), 5);
        PlanModeState plan = new PlanModeState();
        plan.enterPlanMode();
        ToolContext ctx = new ToolContext(dir, new WorkspaceState(), new TodoList(), false, 1000, new CancelToken(),
                new PermissionState(), plan, null, false, null);
        runner.run(SubAgentRunner.Type.GENERAL, "改 a.txt", ctx);
        assertEquals("x", Files.readString(file));
    }

    @Test
    void subAgentReadsDoNotLeakIntoParentWorkspace(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "content");
        Scripted model = new Scripted(List.of(toolCall("Read", Map.of("file_path", file.toString())), text("读完了")));
        SubAgentRunner runner = new SubAgentRunner(new ModelClient(model, config()), parentRegistry(),
                new HookChain(List.of()), config(), 5);
        WorkspaceState parentWs = new WorkspaceState();
        ToolContext ctx = new ToolContext(dir, parentWs, new TodoList(), false, 1000, new CancelToken(),
                new PermissionState());
        SubAgentRunner.Outcome o = runner.run(SubAgentRunner.Type.EXPLORE, "读 a.txt", ctx);
        assertTrue(o.completed());
        assertEquals("读完了", o.text());
        assertFalse(parentWs.hasRead(file));
    }

    @Test
    void cancelledAndStepLimit(@TempDir Path dir) {
        Scripted loop = new Scripted(List.of(toolCall("Glob", Map.of("pattern", "*"))));
        SubAgentRunner runner = new SubAgentRunner(new ModelClient(loop, config()), parentRegistry(),
                new HookChain(List.of()), config(), 3);
        ToolContext ctx = new ToolContext(dir, new WorkspaceState(), new TodoList(), false, 1000, new CancelToken(),
                new PermissionState());
        SubAgentRunner.Outcome o = runner.run(SubAgentRunner.Type.EXPLORE, "loop", ctx);
        assertFalse(o.completed());
        assertEquals(3, o.steps());

        CancelToken token = new CancelToken();
        token.cancel();
        ToolContext cancelled = new ToolContext(dir, new WorkspaceState(), new TodoList(), false, 1000, token,
                new PermissionState());
        assertFalse(runner.run(SubAgentRunner.Type.EXPLORE, "x", cancelled).completed());
    }

    @Test
    void rejectsBadType(@TempDir Path dir) {
        SubAgentRunner runner = new SubAgentRunner(new ModelClient(new Scripted(List.of(text("x"))), config()),
                parentRegistry(), new HookChain(List.of()), config(), 3);
        ToolContext ctx = new ToolContext(dir, new WorkspaceState(), new TodoList(), false, 1000, new CancelToken(),
                new PermissionState());
        ToolResult r = TaskTool.readOnly(runner).execute(ToolCallHelper.of("t", "Task",
                Map.of("description", "d", "prompt", "p", "subagent_type", "general")), ctx);
        assertTrue(r.error());
    }
}
