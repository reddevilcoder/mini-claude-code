package com.minicc.model;

/**
 * 流式输出的回调（M9）。{@link ModelClient#stream} 每收到一段增量就回调一次。
 *
 * <p>回调发生在 Reactor 的 IO 线程上，而不是调用 {@code stream} 的线程；实现方（终端渲染器）
 * 需要自己保证输出串行。所有方法都有空实现，调用方只覆盖关心的事件。
 *
 * <p><b>面试关注：为什么增量只用于展示、不直接写历史？</b>
 * 半截消息写进历史，下一次请求就会带着一条“没说完”的助手消息甚至不完整的 tool_calls，
 * 协议直接非法。所以增量只给人看，完整消息在流结束、聚合成功后才交给 Session。
 */
public interface StreamListener {

    /** 什么都不做的监听器（非流式路径、子任务等）。 */
    StreamListener NONE = new StreamListener() {
    };

    /** 思维链增量（已从“累计值”换算成真正的增量）。 */
    default void onReasoningDelta(String delta) {
    }

    /** 正文增量。 */
    default void onTextDelta(String delta) {
    }

    /**
     * 即将重试一次请求（仅在尚未输出任何增量时才会发生）。
     *
     * @param attempt 即将进行的是第几次尝试（从 2 开始）
     * @param delayMs 退避等待的毫秒数
     * @param reason  上一次失败的原因
     */
    default void onRetry(int attempt, long delayMs, String reason) {
    }
}
