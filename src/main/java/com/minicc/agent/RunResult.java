package com.minicc.agent;

/**
 * 一次 {@link AgentLoop#run} 的结构化结果（M0：Headless / Eval 需要机器可读的结局）。
 *
 * <p>交互模式下 Repl 忽略它（终端已经渲染过了）；Headless 模式把它序列化成 JSON，
 * Eval Harness 再据此统计成功率、步数与工具错误率。
 *
 * <p><b>面试关注：</b>{@code COMPLETED} 只表示模型给出了不含工具调用的最终答复，
 * 并不代表任务真的做对了——"做对没有"由 Eval 的验证命令独立判定，两者刻意分开。
 *
 * @param status     结局类型
 * @param finalText  最终答复文本；出错时为错误信息；其它情况可能为 null
 * @param steps      本轮实际请求模型的次数
 * @param toolCalls  本轮派发的工具调用总数
 * @param toolErrors 其中返回错误（含被拒绝）的次数
 */
public record RunResult(Status status, String finalText, int steps, int toolCalls, int toolErrors) {

    public enum Status {
        /** 模型给出了最终答复。 */
        COMPLETED,
        /** 用户（或上层）取消。 */
        CANCELLED,
        /** 触发 maxSteps 边界。 */
        MAX_STEPS,
        /** 模型调用失败等不可继续的错误。 */
        ERROR
    }
}
