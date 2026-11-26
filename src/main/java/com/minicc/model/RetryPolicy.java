package com.minicc.model;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.LongConsumer;

/**
 * 模型请求的重试策略（M9）：指数退避 + 抖动，只重试“瞬时”错误。
 *
 * <p>可重试：HTTP 408/409/429/5xx、IO 错误、超时（沿异常 cause 链查找）。
 * 不可重试：400/401/403/404/422 之类——重发只会得到同样的结果，还白白消耗配额。
 *
 * <p><b>面试关注：为什么“已经输出过内容”就不能重试？</b>
 * 流式请求一旦把部分正文/思维链推给了用户，重发会得到一份不同的回答，屏幕上就拼出两段互相矛盾的内容；
 * 而且模型请求重试必须和工具执行重试严格区分——这里只重发“无副作用”的模型请求，工具从不自动重跑。
 * 所以本策略只在首个增量到达之前生效，之后的失败直接结束本轮，由用户决定是否重来。
 *
 * <p><b>面试追问：为什么要关掉 SDK 自带的重试？</b>
 * OpenAI SDK 默认会重试 2 次，与这里叠加后最坏是 3×3 次请求，且退避不可见、不可取消。
 * {@link ModelClient} 构建模型时把 SDK 的 maxRetries 设为 0，重试只发生在这一层。
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final LongConsumer sleeper;

    public RetryPolicy(int maxAttempts, long initialBackoffMs) {
        this(maxAttempts, initialBackoffMs, 30_000, RetryPolicy::sleep);
    }

    /** 可测试内核：注入 sleeper，测试里不真正等待。 */
    public RetryPolicy(int maxAttempts, long initialBackoffMs, long maxBackoffMs, LongConsumer sleeper) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = Math.max(0, initialBackoffMs);
        this.maxBackoffMs = maxBackoffMs;
        this.sleeper = sleeper;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * 第 {@code attempt} 次失败后的等待时间：base × 2^(attempt-1)，封顶后取 [50%, 100%] 的随机抖动，
     * 避免大量客户端同一时刻一起重试（惊群）。
     */
    public long backoffMs(int attempt) {
        long exp = initialBackoffMs << Math.min(20, Math.max(0, attempt - 1));
        long capped = Math.min(maxBackoffMs, exp);
        if (capped <= 0) {
            return 0;
        }
        return capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1);
    }

    /** 等待（可被取消回调打断的实现见 ModelClient；这里只负责睡）。 */
    public void pause(long ms) {
        if (ms > 0) {
            sleeper.accept(ms);
        }
    }

    /** 是否瞬时错误：沿 cause 链查找 HTTP 状态码、IO 错误或超时。 */
    public static boolean isRetryable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ModelCancelledException) {
                return false;
            }
            Integer status = statusCode(t);
            if (status != null) {
                return status == 408 || status == 409 || status == 429 || status >= 500;
            }
            if (t instanceof IOException || t instanceof TimeoutException) {
                return true;
            }
            String name = t.getClass().getSimpleName();
            // OpenAI SDK 的 IO 异常、Spring AI 的瞬时异常：按名字识别，避免对具体实现类的编译期依赖。
            if (name.equals("OpenAIIoException") || name.equals("TransientAiException")) {
                return true;
            }
        }
        return false;
    }

    /** OpenAI SDK 的 {@code OpenAIServiceException#statusCode()}；其它异常返回 null。 */
    static Integer statusCode(Throwable t) {
        if (t instanceof com.openai.errors.OpenAIServiceException e) {
            return e.statusCode();
        }
        return null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelCancelledException();
        }
    }
}
