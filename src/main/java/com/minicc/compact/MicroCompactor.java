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
 * Read / Bash(shell) / Grep / Glob / WebSearch / WebFetch / Edit / Write——这是简化的白名单策略），
 * 保留<b>最近 {@code keepRecentToolResults} 个</b>，更早的替换成占位符 {@link #CLEARED}。
 *
 * <p><b>为什么按"空闲时长"触发、而不是按占用？</b>这是全链里最反直觉的一点：
 * 平时每轮请求靠前的部分稳定、能命中模型侧的前缀缓存；一旦在中间"清掉某些旧结果"就打穿了缓存、
 * 后面的缓存可能无法复用。而会话<b>空闲很久</b>后，服务端缓存可能已经过期，
 * 此时清理旧结果通常更容易接受。这里是基于空闲时长的启发式策略，并没有查询服务端的缓存状态，
 * 不能保证缓存已经失效或一定没有额外重算成本。
 *
 * <p>简化：另有一条 cache-editing 主路径（按条数触发、不打穿缓存，还会插入边界消息）这里不做；
 * mini-cc 只做 time-based 这条（留最近 N 个、直接改窗口内容），也不插入边界消息。
 * <b>是否执行由 {@link CompactionManager} 判定</b>（空闲 ≥ {@code microcompactIdleMinutes}，或 {@code /compact} 强制）；
 * 本类被调用时只做投影，不再自己看空闲条件。
 *
 * <p><b>面试关注：清正文与删消息有什么不同？</b>这里只替换 {@code responseData}，保留 id、工具名和
 * 对应的助手调用，因此工具协议配对仍然成立；与 Snip 成对删除调用和结果不同。
 * 两遍扫描先选保留 id、再重建消息，避免对输入历史做原地修改。
 *
 * <p>“可再取”不是无损保证：文件可能已改，搜索结果可能变化，Bash 可能包含副作用，
 * Edit/Write 的旧回执更不能靠重新执行写操作安全恢复。因此占位符表示当前窗口舍弃了细节，
 * 若要查旧事实，应优先读取日志或已有落盘结果，而不是盲目重跑命令。
 * 此投影不写回历史；下一次未满足空闲条件时，旧正文可能再次出现在窗口中，压缩效果并非持久提交。
 */
public class MicroCompactor {

    /** 清空占位符。 */
    static final String CLEARED = "[Old tool result content cleared]";

    /** 允许清理旧正文的工具白名单；“可再取”是近似策略，并不保证可安全重放或无损恢复。 */
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
                // 当前 Session 通常一条消息只有一个结果。若含多个结果，这里按列表正序处理，
                // 且内层没有 kept < keepRecent 判断，保留数可能在这条消息上超过配置值。
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
