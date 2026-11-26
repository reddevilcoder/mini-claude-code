package com.minicc.model;

import com.minicc.agent.CancelToken;
import com.minicc.config.MccConfig;
import com.minicc.persistence.SessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** M9：流式聚合、取消、重试边界与用量统计。全部用假 ChatModel，不走网络。 */
class ModelClientStreamTest {

    private static final MccConfig CONFIG = new MccConfig("m", 40, 200000, "", "http://localhost", false, 1000, "~/.mcc");

    /** 按调用次数返回不同 Flux 的假模型。 */
    private static final class FluxModel implements ChatModel {
        final List<Supplier<Flux<ChatResponse>>> script;
        final AtomicInteger calls = new AtomicInteger();

        FluxModel(List<Supplier<Flux<ChatResponse>>> script) {
            this.script = script;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            int i = calls.getAndIncrement();
            return script.get(Math.min(i, script.size() - 1)).get();
        }
    }

    private static ChatResponse text(String t) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(t))));
    }

    private static ChatResponse reasoning(String cumulative) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .properties(Map.of(SessionStore.REASONING_CONTENT, cumulative)).build())));
    }

    private static ChatResponse finalChunk(List<AssistantMessage.ToolCall> calls, int prompt, int completion) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(prompt, completion)).build());
    }

    private static RetryPolicy noSleep(int attempts) {
        return new RetryPolicy(attempts, 10, 100, ms -> { });
    }

    private static ModelClient client(ChatModel m, int attempts) {
        return new ModelClient(m, CONFIG, noSleep(attempts));
    }

    @Test
    void aggregatesDeltasReasoningToolCallsAndUsage() {
        var call = new AssistantMessage.ToolCall("c1", "function", "Read", "{\"file_path\":\"/a\"}");
        FluxModel m = new FluxModel(List.of(() -> Flux.just(
                reasoning("想一"), reasoning("想一想"), text("我来"), text("读"), finalChunk(List.of(call), 11, 5))));
        List<String> seen = new ArrayList<>();
        AssistantMessage msg = client(m, 3).stream(List.of(new UserMessage("hi")), List.of(), null,
                new StreamListener() {
                    @Override
                    public void onReasoningDelta(String d) {
                        seen.add("R:" + d);
                    }

                    @Override
                    public void onTextDelta(String d) {
                        seen.add("T:" + d);
                    }
                });
        assertEquals(List.of("R:想一", "R:想", "T:我来", "T:读"), seen);  // 累计思维链被换算成增量
        assertEquals("我来读", msg.getText());
        assertEquals("想一想", msg.getMetadata().get(SessionStore.REASONING_CONTENT));
        assertEquals(List.of(call), msg.getToolCalls());
        assertEquals(1, m.calls.get());
    }

    @Test
    void recordsUsageFromLastChunk() {
        FluxModel m = new FluxModel(List.of(() -> Flux.just(text("a"), finalChunk(List.of(), 11, 5))));
        ModelClient c = client(m, 1);
        c.stream(List.of(new UserMessage("hi")), List.of(), null, null);
        assertEquals(1, c.usage().requests());
        assertEquals(11, c.usage().promptTokens());
        assertEquals(5, c.usage().completionTokens());
        assertEquals(11, c.usage().lastPromptTokens());
        assertEquals(0, c.usage().requestsWithoutUsage());
    }

    @Test
    void retriesTransientErrorBeforeFirstToken() {
        FluxModel m = new FluxModel(List.of(
                () -> Flux.error(new RuntimeException("boom", new IOException("reset"))),
                () -> Flux.just(text("ok"), finalChunk(List.of(), 1, 1))));
        List<Integer> retries = new ArrayList<>();
        AssistantMessage msg = client(m, 3).stream(List.of(new UserMessage("hi")), List.of(), null,
                new StreamListener() {
                    @Override
                    public void onRetry(int attempt, long delayMs, String reason) {
                        retries.add(attempt);
                    }
                });
        assertEquals("ok", msg.getText());
        assertEquals(2, m.calls.get());
        assertEquals(List.of(2), retries);
    }

    @Test
    void doesNotRetryAfterOutputStarted() {
        FluxModel m = new FluxModel(List.of(
                () -> Flux.concat(Flux.just(text("半截")), Flux.error(new IOException("reset"))),
                () -> Flux.just(text("不该到这里"))));
        assertThrows(RuntimeException.class,
                () -> client(m, 3).stream(List.of(new UserMessage("hi")), List.of(), null, null));
        assertEquals(1, m.calls.get());
    }

    @Test
    void doesNotRetryNonTransientError() {
        FluxModel m = new FluxModel(List.of(() -> Flux.error(new IllegalArgumentException("bad request"))));
        assertThrows(IllegalArgumentException.class,
                () -> client(m, 3).stream(List.of(new UserMessage("hi")), List.of(), null, null));
        assertEquals(1, m.calls.get());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        FluxModel m = new FluxModel(List.of(() -> Flux.error(new RuntimeException(new IOException("down")))));
        assertThrows(RuntimeException.class,
                () -> client(m, 3).stream(List.of(new UserMessage("hi")), List.of(), null, null));
        assertEquals(3, m.calls.get());
    }

    @Test
    void cancelMidStreamDisposesAndThrows() throws Exception {
        CancelToken token = new CancelToken();
        // 先吐一个分片，然后永远挂着（模拟模型还在慢慢生成）。
        FluxModel m = new FluxModel(List.of(() -> Flux.concat(Flux.just(text("开始")), Flux.never())));
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignore) {
            }
            token.cancel();
        });
        canceller.start();
        long t0 = System.nanoTime();
        assertThrows(ModelCancelledException.class,
                () -> client(m, 3).stream(List.of(new UserMessage("hi")), List.of(), token, null));
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() < 5000);
        assertEquals(1, m.calls.get());   // 取消不触发重试
    }

    @Test
    void costUsesSeparateRates() {
        UsageTracker u = new UsageTracker();
        u.record(new DefaultUsage(1_000_000, 500_000, 1_500_000, null, 400_000L, null));
        Double cost = u.costUsd(new MccConfig.Price(1.0, 4.0, 0.1));
        // 600k × $1 + 400k × $0.1 + 500k × $4 = 0.6 + 0.04 + 2.0
        assertEquals(2.64, cost, 1e-9);
        assertNull(u.costUsd(null));
    }

    @Test
    void retryableClassification() {
        assertTrue(RetryPolicy.isRetryable(new RuntimeException(new IOException())));
        assertFalse(RetryPolicy.isRetryable(new IllegalStateException("x")));
        assertFalse(RetryPolicy.isRetryable(new ModelCancelledException()));
    }
}
