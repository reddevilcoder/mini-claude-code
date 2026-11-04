package com.minicc.compact;

import com.minicc.config.MccConfig;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 第③层：微压缩（mini-cc 只取 time-based 这一条主干）。
 *
 * <p><b>针对对象：②Snip 的输出</b>（链式，不回到原始 messages）。
 * <b>作用：❌不改内存历史、❌不改 JSONL——纯临时映射</b>，只在当次窗口里把旧的可再取结果换成占位符。
 *
 * <p>它只清理<b>可以再取一遍</b>的工具结果（白名单 {@link #COMPACTABLE_TOOLS}：
 * Read / Bash(shell) / Grep / Glob / WebSearch / WebFetch / Edit / Write——它们的内容随时能重新取回来），
 * 保留<b>最近 {@code keepRecentToolResults} 个</b>，更早的替换成占位符 {@link #CLEARED}。
 *
 * <p><b>为什么按"空闲时长"触发、而不是按占用？</b>这是全链里最反直觉的一点：
 * 平时每轮请求靠前的部分稳定、能命中模型侧的前缀缓存；一旦在中间"清掉某些旧结果"就打穿了缓存、
 * 后面全要重算。而会话<b>空闲很久</b>后，前缀缓存本就过期、下次请求注定要重建——这时"先清理再缓存"
 * 几乎不额外付代价。所以微压缩专挑这个"反正要重建缓存"的时刻做，平时绝不轻易动中间历史。
 *
 * <p>简化：另有一条 cache-editing 主路径（按条数触发、不打穿缓存，还会插入边界消息）这里不做；
 * mini-cc 只做 time-based 这条（留最近 N 个、直接改窗口内容），也不插入边界消息。
 * <b>是否执行由 {@link CompactionManager} 判定</b>（空闲 ≥ {@code microcompactIdleMinutes}，或 {@code /compact} 强制）；
 * 本类被调用时只做投影，不再自己看空闲条件。
 */
public class MicroCompactor {

    /** 清空占位符。 */
    static final String CLEARED = "[Old tool result content cleared]";

    /** 可再取一遍的工具白名单（它们的结果随时能重新取回，清掉也不丢信息）。 */
    private static final Set<String> COMPACTABLE_TOOLS = Set.of(
            "Read", "Bash", "Grep", "Glob", "WebSearch", "WebFetch", "Edit", "Write");

    private final MccConfig config;

    public MicroCompactor(MccConfig config) {
        this.config = config;
    }

    /**
     * 对窗口做一次微压缩投影。调用方（{@link CompactionManager}）已经决定要执行，这里不再看空闲条件。
     *
     * <p>从后往前数，保留最近 {@code keepRecentToolResults} 个<b>可再取</b>的工具结果；更早的替换成占位符。
     * 只动白名单工具的结果，其余（如 TodoWrite / AskUserQuestion 的结果）一律不碰。
     */
    public List<Message> apply(List<Message> window) {
        int keepRecent = config.keepRecentToolResults();
        // 1) 先从后往前，标记"最近 keepRecent 个可再取结果"的 id（这些保留原文）。
        Set<String> keepIds = new java.util.HashSet<>();
        int kept = 0;
        for (int i = window.size() - 1; i >= 0 && kept < keepRecent; i--) {
            if (window.get(i) instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                    if (isCompactable(resp)) {
                        keepIds.add(resp.id());
                        kept++;
                    }
                }
            }
        }

        // 2) 再从前往后重建：可再取、且不在"最近保留集"里的，内容换成占位符；其余原样。
        List<Message> out = new ArrayList<>(window.size());
        for (Message message : window) {
            if (message instanceof ToolResponseMessage trm) {
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                boolean changed = false;
                for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                    if (isCompactable(resp) && !keepIds.contains(resp.id())) {
                        responses.add(new ToolResponseMessage.ToolResponse(resp.id(), resp.name(), CLEARED));
                        changed = true;
                    } else {
                        responses.add(resp);
                    }
                }
                out.add(changed ? ToolResponseMessage.builder().responses(responses).build() : trm);
            } else {
                out.add(message);
            }
        }
        return out;
    }

    /** 这条工具结果是否属于"可再取"白名单，且还没被清过（避免重复清）。 */
    private boolean isCompactable(ToolResponseMessage.ToolResponse resp) {
        return COMPACTABLE_TOOLS.contains(resp.name())
                && !CLEARED.equals(resp.responseData());
    }
}
