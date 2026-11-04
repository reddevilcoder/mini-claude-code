package com.minicc.agent;

/**
 * 协作式取消信号（M3）。
 *
 * <p>用户按 Ctrl-C 时置位，控制循环、调度器与 Bash 子进程在各自的"安全检查点"查询它，
 * 从而让任务在不破坏中间状态的前提下停下来——这不是一个强制中断，而是一个<b>信号</b>，
 * 由各处主动检查。它的作用相当于 {@code AbortController}/{@code abortSignal}，
 * 但用最朴素的 volatile 标志位实现。
 *
 * <p>字段用 {@code volatile}：置位发生在 JLine 的信号处理线程，查询发生在循环/工具线程，
 * 需要保证跨线程可见性。
 */
public class CancelToken {

    private volatile boolean cancelled = false;

    /** Repl 装的 Ctrl-C（SIGINT）处理函数在用户按下时调用，置位取消信号。 */
    public void cancel() {
        cancelled = true;
    }

    /** 各安全检查点查询是否已被取消。 */
    public boolean isCancelled() {
        return cancelled;
    }

    /** 每次新任务开始前复位：上一条输入的取消不应影响下一条。 */
    public void reset() {
        cancelled = false;
    }
}
