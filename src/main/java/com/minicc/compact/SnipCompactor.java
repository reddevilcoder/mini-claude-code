package com.minicc.compact;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 第②层：Snip 压缩。
 *
 * <p><b>针对对象：内存 {@code messages} 的副本</b>（投影链起点；此时大结果已是预览+路径）。
 * <b>作用：❌不改内存历史、❌不改 JSONL——纯临时映射</b>，只在当次窗口里删掉低价值 turn。
 *
 * <p>以"一次工具调用 + 它的结果"为单位，直接删掉两类几乎没有参考价值的 turn：
 * <ul>
 *   <li><b>空结果</b>：{@code Grep}/{@code Glob} 没匹配到任何东西（固定文案"没有匹配结果。"/"没有匹配的文件。"）；</li>
 *   <li><b>被拒绝的调用</b>：用户在审批卡片里选了"拒绝"（M3 的 {@code ToolResult.denied}，以"用户拒绝执行工具"开头）。</li>
 * </ul>
 *
 * <p>删除必须<b>成对</b>：既删掉那条 {@link ToolResponseMessage}，也把对应的 {@link AssistantMessage.ToolCall}
 * 从它所在的助手消息上摘掉，保证窗口里不留"悬空工具调用"（成对铁律，和 M4 回放的
 * {@code dropDanglingToolCalls} 是同一个约束，这里复用其结构）。
 *
 * <p>Snip 很便宜，所以<b>每轮都跑</b>，不等接近上限才做；这里选择用这些往返的细节换取更小窗口。
 *
 * <p>简化：这里不在结构化消息块上精确判空/判拒，而是靠工具的固定文案与 {@code denied} 前缀
 * 做启发式判定——比精确判定粗，但足够覆盖教学场景。
 *
 * <p><b>面试关注：为什么要用 callId 成对删除？</b>同一个助手消息可以并列请求多个工具，
 * 不能为了删除其中一个空结果就删掉整条助手消息；按 id 过滤，才能保留同批的其他有效调用。
 * 先扫描收集 id、再重建列表，整体按消息和调用条数线性处理，避免每条结果都反复扫描全历史。
 *
 * <p>“低价值”是策略取舍，不代表没有业务含义：未命中可以说明某种代码不存在，
 * 拒绝原因也可能表达用户边界。当前仅保留其他用户消息和文本，不把这些结果另存成约束；
 * 依赖固定文本的判定还会随工具文案变化失效。它维护调用/结果配对，不能修复任意损坏历史。
 */
public class SnipCompactor {

    /** Grep 无命中时的固定文案。 */
    private static final String GREP_EMPTY = "没有匹配结果。";
    /** Glob 无命中时的固定文案。 */
    private static final String GLOB_EMPTY = "没有匹配的文件。";
    /** 被拒绝结果的前缀（M3 的 {@code ToolResult.denied(...)} 生成）。 */
    private static final String DENIED_PREFIX = "用户拒绝执行工具";

    /** 对窗口做一次 Snip 投影：返回删掉低价值 turn 后的新列表（原列表不动）。 */
    public List<Message> apply(List<Message> history) {
        // 1) 找出要删的 ToolResponse 的 id：空结果 / 被拒绝。
        Set<String> dropIds = collectLowValueResponseIds(history);
        if (dropIds.isEmpty()) {
            return history;
        }
        // 2) 删掉这些 ToolResponseMessage，并从对应 AssistantMessage 上摘掉同 id 的 ToolCall（成对清理）。
        return rebuildWithout(history, dropIds);
    }

    /** 扫一遍历史，收集"空结果 / 被拒绝"的工具结果 id。 */
    private Set<String> collectLowValueResponseIds(List<Message> history) {
        Set<String> ids = new java.util.HashSet<>();
        for (Message message : history) {
            if (message instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                    if (isLowValue(resp)) {
                        ids.add(resp.id());
                    }
                }
            }
        }
        return ids;
    }

    /** 判空/被拒：空结果靠工具产出的固定"未找到"文案，被拒结果以"用户拒绝执行工具"开头。 */
    private boolean isLowValue(ToolResponseMessage.ToolResponse resp) {
        String data = resp.responseData();
        if (data == null) {
            return false;
        }
        String trimmed = data.strip();
        return trimmed.equals(GREP_EMPTY)
                || trimmed.equals(GLOB_EMPTY)
                || trimmed.startsWith(DENIED_PREFIX);
    }

    /**
     * 成对删除：丢掉 id ∈ dropIds 的工具结果，并从助手消息上摘掉同 id 的工具调用。
     * 若某条助手消息的工具调用被摘光、又没有文本，整条丢弃（复用 M4 {@code dropDanglingToolCalls} 的清理风格）。
     */
    private List<Message> rebuildWithout(List<Message> history, Set<String> dropIds) {
        List<Message> out = new ArrayList<>();
        for (Message message : history) {
            if (message instanceof ToolResponseMessage trm) {
                // 保留不在 dropIds 里的结果；整条结果都被删则跳过这条消息。
                List<ToolResponseMessage.ToolResponse> kept = trm.getResponses().stream()
                        .filter(r -> !dropIds.contains(r.id()))
                        .collect(Collectors.toList());
                if (kept.isEmpty()) {
                    continue;
                }
                out.add(kept.size() == trm.getResponses().size()
                        ? trm
                        : ToolResponseMessage.builder().responses(kept).build());
            } else if (message instanceof AssistantMessage assistant) {
                List<AssistantMessage.ToolCall> calls =
                        assistant.getToolCalls() == null ? List.of() : assistant.getToolCalls();
                List<AssistantMessage.ToolCall> kept = calls.stream()
                        .filter(c -> !dropIds.contains(c.id()))
                        .toList();
                // 工具调用全被摘掉、又没有文本 → 整条助手消息丢弃。
                if (kept.isEmpty() && (assistant.getText() == null || assistant.getText().isBlank())) {
                    continue;
                }
                // 没摘则原样保留；摘掉一部分时换一条只带剩余调用的助手消息。
                if (kept.size() == calls.size()) {
                    out.add(assistant);
                } else {
                    // 保留 metadata：思考模式的 reasoningContent 还要随这条助手消息进入下一次请求。
                    out.add(AssistantMessage.builder()
                            .content(assistant.getText() == null ? "" : assistant.getText())
                            .properties(assistant.getMetadata())
                            .toolCalls(kept)
                            .build());
                }
            } else {
                out.add(message);
            }
        }
        return out;
    }
}
