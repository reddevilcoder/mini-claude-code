package com.minicc.model;

import com.minicc.agent.CancelToken;
import com.minicc.config.MccConfig;
import com.minicc.persistence.SessionStore;
import com.minicc.tool.Tool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.Disposable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型边界：整个 harness 与模型交互的唯一入口。
 *
 * <p>它只做一件事：拿到已经拼好的消息和本次可用工具，调用模型，返回一条完整的
 * {@link AssistantMessage}。上下文组装由 {@code ContextAssembler} 完成，助手消息写回会话由
 * {@code Session} 完成，两者都由控制循环编排，不进入本类。
 *
 * <p>Spring AI 2.0 的 {@code ChatModel} 不在框架里执行工具，模型要求的工具调用留在返回的
 * {@link AssistantMessage} 上，由我们自己的控制循环来分发和回填。思考模式的思维链放在这条消息的
 * metadata（键 {@code reasoningContent}）里；下一轮请求时 Spring AI 会把它作为 {@code reasoning_content} 回传。
 *
 * <p><b>面试关注：为什么把模型调用单独封装？</b>
 * 这是适配器边界：上层只依赖“消息 + 工具定义 → 助手消息”，不把审批、文件操作、持久化耦合到模型 SDK。
 * 测试可注入假的 ChatModel，用确定的返回序列验证 Agent 循环。
 *
 * <p><b>面试关注（M9）：流式、重试、取消如何协同？</b>
 * <ul>
 *   <li><b>流式</b>：{@link #stream} 订阅 {@code ChatModel.stream}，增量只推给 {@link StreamListener} 展示；
 *       流结束后再把正文拼接、思维链取全量、工具调用取聚合结果，组装成<b>一条</b>完整消息返回。
 *       半截内容永远不会进入会话历史。</li>
 *   <li><b>取消</b>：通过 {@link CancelToken#onCancel} 注册回调，Ctrl-C 时直接 {@code dispose()} 订阅，
 *       底层 HTTP 连接随之关闭——不必等模型说完。随后抛 {@link ModelCancelledException}。</li>
 *   <li><b>重试</b>：只在“还没向用户输出任何增量”时按 {@link RetryPolicy} 重试；
 *       一旦输出过内容，失败直接上抛。SDK 自带重试被关掉，重试只在这一层发生、可见且可取消。</li>
 *   <li><b>用量</b>：流式下 usage 只出现在最后一个分片（{@code stream_options.include_usage}，
 *       Spring AI 会自动带上），取最后一个非零 usage 记账。</li>
 * </ul>
 */
public class ModelClient {

    private final ChatModel chatModel;
    private final MccConfig config;
    private final RetryPolicy retryPolicy;
    private final UsageTracker usage = new UsageTracker();

    /** 按配置构建 OpenAI 兼容的聊天模型。调用方只传配置，不必接触 Spring AI 的模型类型。 */
    public ModelClient(MccConfig config) {
        this(buildChatModel(config), config);
    }

    /** 注入已有的 {@link ChatModel}（测试用替身）。 */
    public ModelClient(ChatModel chatModel, MccConfig config) {
        this(chatModel, config, retryPolicyOf(config));
    }

    /** 测试用：同时注入重试策略（例如不真正 sleep 的策略）。 */
    public ModelClient(ChatModel chatModel, MccConfig config, RetryPolicy retryPolicy) {
        this.chatModel = chatModel;
        this.config = config;
        this.retryPolicy = retryPolicy;
    }

    private static RetryPolicy retryPolicyOf(MccConfig config) {
        MccConfig.ModelSettings m = config.modelSettings() == null
                ? MccConfig.DEFAULT_MODEL_SETTINGS : config.modelSettings();
        return new RetryPolicy(m.retryMaxAttempts(), m.retryInitialBackoffMs());
    }

    private static ChatModel buildChatModel(MccConfig config) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey() == null ? "" : config.apiKey())
                .model(config.model())
                // M9：关闭 SDK 自带重试，重试统一由 RetryPolicy 负责（避免叠加放大、且可被取消）。
                .maxRetries(0)
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }

    private Prompt prompt(List<Message> messages, Collection<Tool> tools) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.model())
                // GPT-6 Luna 的 Chat Completions 工具调用要求关闭 reasoning。
                // 这是请求级选项：每次 Prompt 都要携带，不能只设在模型默认 options 中。
                .reasoningEffort("gpt-6-luna".equals(config.model()) ? "none" : null)
                .toolCallbacks(ToolsConverter.toToolCallbacks(tools))
                .build();
        return new Prompt(messages, options);
    }

    /**
     * 同步请求模型一轮（压缩、记忆提取等“后台”调用使用）。瞬时错误按策略重试。
     *
     * @param messages 本次请求的完整消息（已由上下文管线拼好）
     * @param tools    本次可用的工具（转成工具定义随请求发送）
     * @return 模型返回的助手消息，不在这里改写
     */
    public AssistantMessage call(List<Message> messages, Collection<Tool> tools) {
        Prompt prompt = prompt(messages, tools);
        for (int attempt = 1; ; attempt++) {
            try {
                ChatResponse resp = chatModel.call(prompt);
                // M0：记录服务端返回的真实用量（所有走 ModelClient 的调用都会被计入）。
                usage.record(resp.getMetadata() == null ? null : resp.getMetadata().getUsage());
                // 这里取首个生成结果，约定模型响应至少包含一个结果。
                return resp.getResult().getOutput();
            } catch (RuntimeException e) {
                if (!shouldRetry(e, attempt)) {
                    throw e;
                }
                backoff(attempt, e, null, StreamListener.NONE);
            }
        }
    }

    /**
     * 流式请求模型一轮（M9）：增量推给 listener，结束后返回聚合好的完整助手消息。
     *
     * @param cancelToken 可为 null；取消时立即断开连接并抛 {@link ModelCancelledException}
     * @param listener    增量回调，发生在 IO 线程上
     */
    public AssistantMessage stream(List<Message> messages, Collection<Tool> tools,
                                   CancelToken cancelToken, StreamListener listener) {
        Prompt prompt = prompt(messages, tools);
        StreamListener l = listener == null ? StreamListener.NONE : listener;
        for (int attempt = 1; ; attempt++) {
            Accumulator acc = new Accumulator(l);
            try {
                AssistantMessage message = streamOnce(prompt, cancelToken, acc);
                usage.record(acc.usage);
                return message;
            } catch (ModelCancelledException e) {
                throw e;
            } catch (RuntimeException e) {
                // 已经向用户输出过增量 → 不能重试（重发会得到一份不同的回答）。
                if (acc.emitted || !shouldRetry(e, attempt)) {
                    throw e;
                }
                backoff(attempt, e, cancelToken, l);
            }
        }
    }

    private AssistantMessage streamOnce(Prompt prompt, CancelToken cancelToken, Accumulator acc) {
        if (cancelToken != null && cancelToken.isCancelled()) {
            throw new ModelCancelledException();
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<Disposable> subscription = new AtomicReference<>();

        Disposable d = chatModel.stream(prompt).subscribe(
                acc::accept,
                err -> {
                    failure.set(err);
                    done.countDown();
                },
                done::countDown);
        subscription.set(d);

        CancelToken.Registration reg = cancelToken == null ? () -> { }
                : cancelToken.onCancel(() -> {
                    cancelled.set(true);
                    Disposable s = subscription.get();
                    if (s != null) {
                        s.dispose(); // 断开订阅 → 关闭底层 HTTP 流，模型侧停止计费生成
                    }
                    done.countDown();
                });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            d.dispose();
            throw new ModelCancelledException();
        } finally {
            reg.close();
        }
        if (cancelled.get()) {
            throw new ModelCancelledException();
        }
        Throwable err = failure.get();
        if (err != null) {
            throw err instanceof RuntimeException re ? re : new RuntimeException(err);
        }
        return acc.build();
    }

    private boolean shouldRetry(RuntimeException e, int attempt) {
        return attempt < retryPolicy.maxAttempts() && RetryPolicy.isRetryable(e);
    }

    /** 退避等待：按 100ms 切片睡，期间若被取消立即放弃重试。 */
    private void backoff(int attempt, RuntimeException cause, CancelToken cancelToken, StreamListener l) {
        long delay = retryPolicy.backoffMs(attempt);
        l.onRetry(attempt + 1, delay, String.valueOf(cause.getMessage()));
        long remaining = delay;
        while (remaining > 0) {
            if (cancelToken != null && cancelToken.isCancelled()) {
                throw new ModelCancelledException();
            }
            long slice = Math.min(100, remaining);
            retryPolicy.pause(slice);
            remaining -= slice;
        }
        if (cancelToken != null && cancelToken.isCancelled()) {
            throw new ModelCancelledException();
        }
    }

    /** 本客户端累计的模型用量（M0）。 */
    public UsageTracker usage() {
        return usage;
    }

    /**
     * 单次流式请求的聚合器：把分片拼回一条完整消息。
     *
     * <p>Spring AI 的行为（M9 spike 实测）：正文 {@code getText()} 是增量；metadata 里的
     * {@code reasoningContent} 是<b>累计值</b>；分片的 tool_calls 由框架聚合后完整出现在最后一个分片上；
     * usage 只在最后一个分片非零。聚合器对两种思维链形式都兼容：以前缀判断是累计还是增量。
     */
    static final class Accumulator {
        private final StreamListener listener;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final Map<String, AssistantMessage.ToolCall> toolCalls = new LinkedHashMap<>();
        private Usage usage;
        volatile boolean emitted = false;

        Accumulator(StreamListener listener) {
            this.listener = listener;
        }

        void accept(ChatResponse chunk) {
            if (chunk.getMetadata() != null) {
                Usage u = chunk.getMetadata().getUsage();
                if (u != null && (nz(u.getPromptTokens()) || nz(u.getCompletionTokens()))) {
                    usage = u;
                }
            }
            if (chunk.getResults() == null || chunk.getResults().isEmpty() || chunk.getResult() == null) {
                return;
            }
            AssistantMessage out = chunk.getResult().getOutput();
            if (out == null) {
                return;
            }
            Object r = out.getMetadata() == null ? null : out.getMetadata().get(SessionStore.REASONING_CONTENT);
            if (r instanceof String rs && !rs.isEmpty()) {
                String current = reasoning.toString();
                String delta;
                if (rs.startsWith(current)) {        // 累计值：只取新增部分
                    delta = rs.substring(current.length());
                } else {                              // 增量值：直接追加
                    delta = rs;
                }
                if (!delta.isEmpty()) {
                    reasoning.append(delta);
                    emitted = true;
                    listener.onReasoningDelta(delta);
                }
            }
            String t = out.getText();
            if (t != null && !t.isEmpty()) {
                text.append(t);
                emitted = true;
                listener.onTextDelta(t);
            }
            if (out.getToolCalls() != null) {
                for (AssistantMessage.ToolCall call : out.getToolCalls()) {
                    // 以 id 去重：框架已聚合时同一调用可能在多个分片重复出现，后到的更完整。
                    String key = call.id() == null || call.id().isEmpty() ? "idx-" + toolCalls.size() : call.id();
                    toolCalls.put(key, call);
                }
            }
        }

        private static boolean nz(Integer v) {
            return v != null && v != 0;
        }

        AssistantMessage build() {
            Map<String, Object> props = new LinkedHashMap<>();
            if (!reasoning.isEmpty()) {
                props.put(SessionStore.REASONING_CONTENT, reasoning.toString());
            }
            return AssistantMessage.builder()
                    .content(text.toString())
                    .properties(props)
                    .toolCalls(List.copyOf(toolCalls.values()))
                    .build();
        }
    }
}
