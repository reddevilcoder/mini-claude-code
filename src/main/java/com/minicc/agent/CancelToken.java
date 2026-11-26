package com.minicc.agent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
 *
 * <p><b>面试关注：为什么 volatile 足够，何时又不够？</b>
 * 当前操作只有独立的布尔读写，不包含“读取后计算再更新”这样的复合操作，
 * volatile 的可见性与有序性已满足共享取消信号的需求；它不提供互斥，也不会主动停止线程。
 * 如果要实现“仅第一个取消者执行清理”则需要 CAS 或加锁，单靠 volatile 不够。
 *
 * <p><b>面试追问：取消延迟由什么决定？</b>
 * 由检查点频率和底层阻塞操作决定，而不是由标志本身决定。M9 起流式模型请求通过 {@link #onCancel} 回调被即时打断；
 * 已完成的文件修改也不会撤销。reset 依赖 REPL 单任务串行的使用方式；
 * 若未来并发运行多个任务，应每个任务独立持有 token，避免一个任务复位另一个任务的取消信号。
 */
public class CancelToken {

    private volatile boolean cancelled = false;

    /**
     * M9：取消回调。轮询只适合“有检查点”的代码；阻塞在网络流上的模型请求没有检查点，
     * 需要在取消瞬间被主动打断（dispose 订阅）。用 CopyOnWriteArrayList：注册/注销在工作线程，
     * 触发在信号线程，遍历期间允许并发修改。
     */
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Repl 装的 Ctrl-C（SIGINT）处理函数在用户按下时调用，置位取消信号，并触发已注册的回调。 */
    public void cancel() {
        cancelled = true;
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException ignore) {
                // 单个回调失败不影响其他回调与取消本身。
            }
        }
    }

    /**
     * M9：注册取消回调，返回的句柄用于注销（建议 try-with-resources）。
     * 注册时若已处于取消态，回调立即执行一次——避免“先取消、后注册”的竞态下请求永远不被打断。
     * 回调可能执行多次（取消与注册竞态时），实现应保持幂等。
     */
    public Registration onCancel(Runnable callback) {
        listeners.add(callback);
        if (cancelled) {
            callback.run();
        }
        return () -> listeners.remove(callback);
    }

    /** 取消回调的注销句柄。 */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
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
