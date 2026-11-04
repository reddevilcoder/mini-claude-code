package com.minicc.compact;

import com.minicc.cli.Renderer;
import com.minicc.config.MccConfig;
import com.minicc.session.Session;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 压缩编排核心：持有第①层落盘器（入库时用）与 ②–⑤ 四层投影器（每轮请求时用）。
 *
 * <p>五层按固定顺序调用：工具结果预算 → Snip → 微压缩 → 上下文折叠 → 自动压缩。
 * 核心思想是<b>分层、便宜→贵、链式短路</b>：
 * 每一层比上一层更贵、更可能丢信息；前面的层腾出足够空间，后面的层就直接 no-op。
 *
 * <p>两个时机务必分清：
 * <ul>
 *   <li><b>{@link #offloadToolResult}</b>——第①层，工具结果写入内存历史/日志<b>之前</b>调用：
 *       超阈值的大结果当场落盘、入库那条换成"预览+路径"。这是唯一改写持久状态的一层，但原文只是搬进
 *       {@code tool-results/}、没丢。不在每轮投影链里（产生时定稿、天然冻结）。</li>
 *   <li><b>{@link #compact} / {@link #compactNow}</b>——②–⑤ 四层，
 *       每轮请求前把内存历史投影成"发给模型的窗口"。其中 ②③④ 是<b>纯读投影</b>（不改内存历史、不改 JSONL）；
 *       <b>只有第⑤层自动压缩会就地改写内存历史</b>——把"摘要 + 最近几条原文"写回 {@code messages}
 *       （{@link com.minicc.session.Session#replaceHistoryInMemory}），但<b>仍不碰 JSONL</b>
 *       （完整原文留在会话日志与 {@code tool-results/}，{@code /resume} 读回全量）。</li>
 * </ul>
 */
public class CompactionManager {

    private final ToolResultCompactor toolResultCompactor;   // 第①层：工具结果入库时落盘，不在 compact 链里
    private final SnipCompactor snip;        // 第②层
    private final MicroCompactor micro;      // 第③层
    private final ContextCollapser collapser;// 第④层
    private final AutoCompactor auto;        // 第⑤层
    private final MccConfig config;
    private final Renderer renderer;
    /** 上次请求时刻。构造时记为现在，用来算第③层微压缩的空闲时长。 */
    private Instant lastRequestAt = Instant.now();

    public CompactionManager(ToolResultCompactor toolResultCompactor,
                             SnipCompactor snip,
                             MicroCompactor micro,
                             ContextCollapser collapser,
                             AutoCompactor auto,
                             MccConfig config,
                             Renderer renderer) {
        this.toolResultCompactor = toolResultCompactor;
        this.snip = snip;
        this.micro = micro;
        this.collapser = collapser;
        this.auto = auto;
        this.config = config;
        this.renderer = renderer;
    }

    /**
     * 第①层：工具结果写入历史/日志前调用——超阈值的大结果落盘、入库那条换成"预览+路径"。
     * 普通消息与未超阈值结果原样返回。
     */
    public ToolResponseMessage offloadToolResult(ToolResponseMessage result) {
        return toolResultCompactor.apply(result);
    }

    /**
     * {@code /compact} 手动触发：<b>不看阈值</b>强制把 Snip→微压缩→折叠→自动压缩都跑一遍，
     * 并打印"刻意可见"的分层过程与前后 Token。
     */
    public void compactNow(Session session) {
        List<Message> history = session.history();          // 大结果早在入库时就落过盘了
        int before = TokenEstimator.ofMessages(history);
        if (renderer != null) {
            renderer.compactionStart();
        }

        List<Message> window = snip.apply(history);          // ②Snip 起步（①不在投影链里）
        note("Snip：清理空结果 / 被拒绝的调用", before, TokenEstimator.ofMessages(window));

        int t = TokenEstimator.ofMessages(window);
        window = micro.apply(window);                        // 手动时无视空闲，顺带清理可再取的旧结果
        note("微压缩：清理较早可再取的工具结果", t, TokenEstimator.ofMessages(window));

        t = TokenEstimator.ofMessages(window);
        window = collapser.apply(window);                    // 强制折叠（不看阈值）
        note("上下文折叠：把较早对话折叠成结构化摘要", t, TokenEstimator.ofMessages(window));

        t = TokenEstimator.ofMessages(window);
        window = auto.apply(window, session);                // 强制自动压缩（调模型；不看阈值；就地改写内存历史）
        note("自动压缩：对其余历史做一次模型摘要（已替换内存历史，JSONL 不动）", t, TokenEstimator.ofMessages(window));

        int after = TokenEstimator.ofMessages(window);
        if (renderer != null) {
            renderer.compactionSummary(before, after);       // 打印"X → Y Token"
        }
    }

    /**
     * 每轮请求前：把内存历史投影成精简窗口（②–⑤，便宜→贵、链式短路）。
     * 第①层不在这里——它在工具结果入库时就执行过了。
     *
     * <p>会提交状态（更新"上次请求时刻"）。②③④ 是纯读投影；跨过自动压缩阈值时⑤会调模型，
     * 并把"摘要+最近几条原文"写回 {@code session} 的内存历史（不动 JSONL）。
     * 供 {@link com.minicc.context.CompactionStage} 用。
     *
     * @param session 承载内存历史的会话（⑤自动压缩写回它）
     */
    public List<Message> compact(List<Message> history, Session session) {
        double limit = config.contextLimit();

        // ② Snip：删掉空结果 / 被拒绝的调用（便宜，成对删除保证窗口合法）。
        List<Message> window = snip.apply(history);

        // 第③层是否该跑：距上次请求的空闲时长 ≥ microcompactIdleMinutes。
        long idleMinutes = Duration.between(lastRequestAt, Instant.now()).toMinutes();
        boolean microDue = idleMinutes >= config.microcompactIdleMinutes();
        this.lastRequestAt = Instant.now();
        // 腾完 Snip，若已回落到折叠阈值以下、且不需微压缩，③④⑤ 都不用做（链式短路）。
        if (TokenEstimator.ofMessages(window) < limit * config.collapseAt() && !microDue) {
            return window;
        }

        // ③ 微压缩：仅"会话空闲够久后再继续"才触发（清理可再取的旧结果，保留最近 N 个）。
        if (microDue) {
            window = micro.apply(window);
        }

        // ④ 上下文折叠：仍 ≥ 90% → 把较早整轮折叠成结构化摘要（不调模型）。
        if (TokenEstimator.ofMessages(window) >= limit * config.collapseAt()) {
            window = collapser.apply(window);
        }

        // ⑤ 自动压缩：仍 ≥ 95% 时触发——调模型摘要整段历史（含紧急压缩兜底），并就地改写内存历史。
        if (TokenEstimator.ofMessages(window) >= limit * config.autoCompactAt()) {
            window = auto.apply(window, session);
        }

        return window;
    }

    /** 测试用：直接设置上次请求时刻（例如把它拨回 61 分钟前以触发微压缩）。 */
    void setLastRequestAt(Instant instant) {
        this.lastRequestAt = instant;
    }

    /** 打印一层的"刻意可见"过程（X→Y Token）。 */
    private void note(String label, int before, int after) {
        if (renderer != null) {
            renderer.compactionNote(label + "（" + before + " → " + after + " Token）");
        }
    }
}
