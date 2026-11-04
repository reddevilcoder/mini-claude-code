package com.minicc.agent;

import com.minicc.cli.Renderer;
import com.minicc.config.MccConfig;
import com.minicc.context.ContextAssembler;
import com.minicc.context.HistoryStage;
import com.minicc.context.SystemPrompt;
import com.minicc.context.SystemPromptStage;
import com.minicc.model.ModelClient;
import com.minicc.persistence.SessionStore;
import com.minicc.session.Session;
import com.minicc.tool.ToolRegistry;
import com.minicc.tool.builtin.GlobTool;
import com.minicc.tool.builtin.GrepTool;
import com.minicc.tool.builtin.ReadTool;
import com.minicc.tool.hook.HookChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentLoop 的端到端测试：用一个假的 ChatModel 代替真实模型，
 * 验证"决策—行动—观察"循环能跑通，不依赖网络或 API Key。
 *
 * <p>脚本：第一轮模型要求调用 Read 读一个真实文件；第二轮不再调用工具、给出最终答复，循环结束。
 */
class AgentLoopTest {

    @Test
    void runsDecideActObserveLoop(@TempDir Path dir) throws IOException {
        // 准备一个待读取的真实文件。
        Path file = dir.resolve("Hello.java");
        Files.writeString(file, "class Hello {}\n");
        String argsJson = "{\"file_path\":\"" + file.toAbsolutePath() + "\"}";

        // 假模型：第一次返回 Read 工具调用，第二次返回最终答复。
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(
                AssistantMessage.builder()
                        .content("先读取文件")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "Read", argsJson)))
                        .build(),
                new AssistantMessage("这是一个空的 Hello 类。")
        ));

        MccConfig config = new MccConfig("fake-model", 40, 200000, "", "http://localhost", false, 120000,
                MccConfig.DEFAULT_STATE_DIR);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadTool());
        registry.register(new GrepTool());
        registry.register(new GlobTool());

        ContextAssembler assembler = new ContextAssembler(List.of(
                new SystemPromptStage(SystemPrompt.TEXT), new HistoryStage()));
        ModelClient modelClient = new ModelClient(chatModel, config);
        AgentLoop loop = new AgentLoop(modelClient, assembler, registry, new HookChain(List.of()),
                new Renderer(), config, new CancelToken());

        Session session = new Session(dir);
        session.appendUserMessage("Hello 类是做什么的？");
        loop.run(session);

        // 模型被调用了两次（第一次决定读文件，第二次给出答复）。
        assertEquals(2, chatModel.callCount);
        // 历史里应包含：用户消息、助手(含工具调用)、工具结果、助手(最终答复)。
        assertEquals(4, session.size());
    }

    @Test
    void printsReasoningBeforeContent(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Hello.java");
        Files.writeString(file, "class Hello {}\n");
        String argsJson = "{\"file_path\":\"" + file.toAbsolutePath() + "\"}";

        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(
                AssistantMessage.builder()
                        .content("先读取文件")
                        .properties(Map.of(SessionStore.REASONING_CONTENT, "先看 Hello.java 里有什么。"))
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "Read", argsJson)))
                        .build(),
                AssistantMessage.builder()
                        .content("这是一个空的 Hello 类。")
                        .properties(Map.of(SessionStore.REASONING_CONTENT, "文件里只有类声明。"))
                        .build()
        ));

        MccConfig config = new MccConfig("fake-model", 40, 200000, "", "http://localhost", false, 120000,
                MccConfig.DEFAULT_STATE_DIR);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadTool());
        ContextAssembler assembler = new ContextAssembler(List.of(
                new SystemPromptStage(SystemPrompt.TEXT), new HistoryStage()));
        AgentLoop loop = new AgentLoop(new ModelClient(chatModel, config), assembler, registry,
                new HookChain(List.of()), new Renderer(), config, new CancelToken());

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            Session session = new Session(dir);
            session.appendUserMessage("Hello 类是做什么的？");
            loop.run(session);
        } finally {
            System.setOut(previous);
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("思考：先看 Hello.java 里有什么。"));
        assertTrue(output.contains("先读取文件"));
        assertTrue(output.contains("思考：文件里只有类声明。"));
        assertTrue(output.contains("这是一个空的 Hello 类。"));
        assertTrue(output.indexOf("思考：先看 Hello.java 里有什么。") < output.indexOf("先读取文件"));
        assertTrue(output.indexOf("思考：文件里只有类声明。") < output.indexOf("这是一个空的 Hello 类。"));
    }

    /** 按预设脚本逐次返回助手消息的假 ChatModel。 */
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
