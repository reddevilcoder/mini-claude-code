package com.minicc.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicc.config.MccConfig;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * ModelClient 只负责一次模型调用：传入已经拼好的消息和工具，原样返回模型的 AssistantMessage。
 */
class ModelClientTest {

    @Test
    void returnsTheModelMessageWithoutMutatingTheRequest() {
        List<Message> messages = List.of(new UserMessage("读一下 Hello.java"));
        AssistantMessage reply = AssistantMessage.builder()
                .content("先读取文件")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "Read", "{\"file_path\":\"Hello.java\"}")))
                .build();
        CapturingChatModel chatModel = new CapturingChatModel(reply);

        ModelClient client = new ModelClient(chatModel, config());
        AssistantMessage turn = client.call(messages, List.of(new StubTool("Read")));

        assertSame(reply, turn);
        assertEquals(messages, chatModel.prompt.getInstructions());
        OpenAiChatOptions options = (OpenAiChatOptions) chatModel.prompt.getOptions();
        assertEquals("fake-model", options.getModel());
        assertNull(options.getReasoningEffort());
        assertEquals("Read", options.getToolCallbacks().get(0).getToolDefinition().name());
    }

    @Test
    void disablesReasoningForGpt6LunaToolCalls() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requestPath.set(exchange.getRequestURI().getPath());
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = """
                        {
                          "id": "chatcmpl-local-test",
                          "object": "chat.completion",
                          "created": 1,
                          "model": "gpt-6-luna",
                          "choices": [{
                            "index": 0,
                            "message": {"role": "assistant", "content": "local response"},
                            "finish_reason": "stop"
                          }],
                          "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
                        }
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            MccConfig config = new MccConfig("gpt-6-luna", 40, 200000, "test-only-key", baseUrl,
                    false, 120000, MccConfig.DEFAULT_STATE_DIR);
            ModelClient client = new ModelClient(config);

            AssistantMessage reply = client.call(List.of(new UserMessage("读取文件")), List.of(new StubTool("Read")));

            assertEquals("/v1/chat/completions", requestPath.get());
            JsonNode body = new ObjectMapper().readTree(requestBody.get());
            assertEquals("gpt-6-luna", body.path("model").asText());
            assertEquals("none", body.path("reasoning_effort").asText());
            assertEquals(1, body.path("tools").size());
            assertEquals("function", body.path("tools").path(0).path("type").asText());
            assertEquals("Read", body.path("tools").path(0).path("function").path("name").asText());
            assertEquals("local response", reply.getText());
        } finally {
            server.stop(0);
        }
    }

    private static MccConfig config() {
        return new MccConfig("fake-model", 40, 200000, "", "http://localhost", false, 120000,
                MccConfig.DEFAULT_STATE_DIR);
    }

    private static final class CapturingChatModel implements ChatModel {
        private final AssistantMessage reply;
        Prompt prompt;

        CapturingChatModel(AssistantMessage reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt = prompt;
            return new ChatResponse(List.of(new Generation(reply)));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            throw new UnsupportedOperationException("测试不使用流式接口");
        }
    }

    private record StubTool(String name) implements Tool {
        @Override
        public String description() {
            return "stub";
        }

        @Override
        public Map<String, Object> paramsSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public boolean isConcurrencySafe() {
            return true;
        }

        @Override
        public ToolResult execute(AssistantMessage.ToolCall call, ToolContext context) {
            throw new UnsupportedOperationException("模型边界测试不执行工具");
        }
    }
}
