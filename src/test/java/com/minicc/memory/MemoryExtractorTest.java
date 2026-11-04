package com.minicc.memory;

import com.minicc.cli.Renderer;
import com.minicc.config.MccConfig;
import com.minicc.model.ModelClient;
import com.minicc.permission.ApprovalHook;
import com.minicc.permission.Approver;
import com.minicc.session.Session;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolRegistry;
import com.minicc.tool.builtin.EditTool;
import com.minicc.tool.builtin.GlobTool;
import com.minicc.tool.builtin.GrepTool;
import com.minicc.tool.builtin.ReadTool;
import com.minicc.tool.builtin.WriteTool;
import com.minicc.tool.hook.HookChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * MemoryExtractor 的端到端测试：用一个假的 ChatModel 代替真实模型，验证受限提取回合能把记忆写进记忆目录，
 * 且记忆写入走审批钩子的「记忆目录内自动放行」——不需要人工审批。
 */
class MemoryExtractorTest {

    @Test
    void extractionWritesMemoryFileIntoMemoryDir(@TempDir Path memoryDir) {
        AutoMemory autoMemory = new AutoMemory(memoryDir);
        autoMemory.ensureMemoryDirExists();

        Path memoryFile = memoryDir.resolve("user_role.md");
        String content = """
                ---
                name: 用户角色
                description: 用户是数据科学家，关注可观测性/日志
                type: user
                ---

                用户是数据科学家，当前关注日志与可观测性。
                """;
        String writeArgs = ToolCallHelper.of("w1", "Write",
                Map.of("file_path", memoryFile.toString(), "content", content)).arguments();

        // 假模型：第一轮发起 Write 写入记忆文件；第二轮不再调用工具、收尾。
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(
                AssistantMessage.builder()
                        .content("记录一条 user 记忆")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("w1", "function", "Write", writeArgs)))
                        .build(),
                new AssistantMessage("已更新记忆。")
        ));

        MccConfig config = new MccConfig("fake", 40, 200000, "", "http://localhost", false, 120000,
                MccConfig.DEFAULT_STATE_DIR);
        ModelClient modelClient = new ModelClient(chatModel, config);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadTool());
        registry.register(new GrepTool());
        registry.register(new GlobTool());
        registry.register(new EditTool());
        registry.register(new WriteTool());

        // 审批钩子里放一个「一旦被调用就失败」的 Approver：记忆写入应自动放行，不该走到它。
        Approver failIfPrompted = (call, tool, ctx) -> {
            fail("记忆写入不应触发审批卡片");
            return null;
        };
        HookChain hookChain = new HookChain(List.of(new ApprovalHook(failIfPrompted)));

        MemoryExtractor extractor =
                new MemoryExtractor(modelClient, registry, hookChain, autoMemory, config, new Renderer());

        Session session = new Session(memoryDir);
        List<Message> turnMessages = List.of(
                new UserMessage("I'm a data scientist investigating what logging we have in place"));

        extractor.extract(session, turnMessages);

        assertEquals(2, chatModel.callCount, "提取回合应调用模型两次（写入 + 收尾）");
        assertTrue(Files.exists(memoryFile), "记忆文件应被写入记忆目录");
        try {
            assertTrue(Files.readString(memoryFile).contains("数据科学家"), "记忆文件应含写入内容");
        } catch (Exception e) {
            fail("读取记忆文件失败：" + e.getMessage());
        }
    }

    /** 按预设脚本逐次返回助手消息的假 ChatModel（与 AgentLoopTest 同款）。 */
    private static class ScriptedChatModel implements ChatModel {
        private final List<AssistantMessage> script;
        int callCount = 0;

        ScriptedChatModel(List<AssistantMessage> script) {
            this.script = script;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            AssistantMessage next = script.get(Math.min(callCount, script.size() - 1));
            callCount++;
            return new ChatResponse(List.of(new Generation(next)));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            throw new UnsupportedOperationException("测试不使用流式接口");
        }
    }
}
