package com.minicc.model;

import org.springframework.ai.chat.metadata.Usage;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型用量账本：累计请求次数与服务端返回的真实 Token 数（不是估算值）。
 *
 * <p>挂在 {@link ModelClient} 上，所以主循环、自动压缩、记忆提取三条调用路径的消耗都会被计入。
 * 计数用 {@link AtomicLong}：后续 SubAgent 可能并发调用同一个 ModelClient。
 *
 * <p><b>面试关注：</b>服务端不返回 usage（部分兼容实现、或流式未开启 include_usage）时，
 * 对应请求只计次数、不计 Token，{@link #requestsWithoutUsage()} 会暴露这种缺口，避免把 0 当成真实消耗。
 */
public class UsageTracker {

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong requestsWithoutUsage = new AtomicLong();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong cacheReadTokens = new AtomicLong();
    /** M9：最近一次请求服务端报告的 prompt Token——这是“当前上下文真实大小”，/context 用它校准估算。 */
    private final AtomicLong lastPromptTokens = new AtomicLong(-1);

    /** 记录一次请求的用量；usage 为 null 或字段缺失时只计次数。 */
    public void record(Usage usage) {
        requests.incrementAndGet();
        Integer prompt = usage == null ? null : usage.getPromptTokens();
        Integer completion = usage == null ? null : usage.getCompletionTokens();
        if (prompt == null && completion == null) {
            requestsWithoutUsage.incrementAndGet();
            return;
        }
        promptTokens.addAndGet(prompt == null ? 0 : prompt);
        if (prompt != null) {
            lastPromptTokens.set(prompt);
        }
        completionTokens.addAndGet(completion == null ? 0 : completion);
        Long cached = usage.getCacheReadInputTokens();
        if (cached != null) {
            cacheReadTokens.addAndGet(cached);
        }
    }

    public long requests() {
        return requests.get();
    }

    public long requestsWithoutUsage() {
        return requestsWithoutUsage.get();
    }

    public long promptTokens() {
        return promptTokens.get();
    }

    public long completionTokens() {
        return completionTokens.get();
    }

    public long cacheReadTokens() {
        return cacheReadTokens.get();
    }

    /** 最近一次请求的真实 prompt Token；还没有任何带 usage 的请求时返回 -1。 */
    public long lastPromptTokens() {
        return lastPromptTokens.get();
    }

    /**
     * M9：按单价折算累计费用（美元）。缓存命中部分按 cacheRead 单价计（未配置则按输入价）。
     *
     * <p>面试关注：费用是“按请求累加 Token × 单价”，而不是 Token 总数 × 平均价——
     * 输入、输出、缓存命中的单价相差可达 10 倍，混在一起算会严重失真。
     * 价格必须来自配置：厂商随时调价，代码里写死的价格迟早是错的。
     *
     * @return 费用；price 为 null 时返回 null（未配置价格，不猜）
     */
    public Double costUsd(com.minicc.config.MccConfig.Price price) {
        if (price == null) {
            return null;
        }
        long cached = Math.min(cacheReadTokens.get(), promptTokens.get());
        long uncached = promptTokens.get() - cached;
        double cacheRate = price.cacheReadPerMTok() != null ? price.cacheReadPerMTok() : price.inputPerMTok();
        return (uncached * price.inputPerMTok() + cached * cacheRate
                + completionTokens.get() * price.outputPerMTok()) / 1_000_000.0;
    }

    public long totalTokens() {
        return promptTokens.get() + completionTokens.get();
    }
}
