package com.minicc.compact;

import com.minicc.cli.Renderer;
import com.minicc.model.ModelClient;
import com.minicc.session.Session;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 第⑤层：自动压缩。这是链里<b>唯一需要调模型</b>、也最贵的一层。
 *
 * <p><b>针对对象：④折叠的输出</b>（链式）。<b>作用：✅改内存历史（替换 {@code messages}）、❌不改 JSONL</b>。
 * 这是 ②–⑤ 里<b>唯一会改写内存历史</b>的一层——就地改写内存里的 REPL 消息数组；
 * 但 mini-cc <b>刻意不碰 JSONL</b>，完整原文仍在会话日志与 {@code tool-results/} 里，{@code /resume} 读回全量。
 *
 * <p>窗口在折叠之后仍 ≥ 95%（{@code autoCompactAt}）时触发：把整段历史交给模型做一次摘要
 * （提示词见 {@link CompactionPromptText}，固定九段、只许输出文字），然后用
 * <b>"一条摘要消息 + 最近几条原文"</b>通过 {@link Session#replaceHistoryInMemory} <b>整体替换掉内存历史</b>。
 * 摘要提示的第 6 段要求逐条复述用户消息，最近几轮的用户原话还会原样保留，以降低意图丢失风险。
 * 但这是有损摘要，模型可能遗漏或改写，不能保证所有意图不丢失；若最近轮次或摘要本身仍很大，
 * 下一轮还可能再次达到阈值，本实现没有压后预算验证或独立的摘要缓存。
 *
 * <p>含<b>紧急压缩兜底</b>：若摘要请求自身都因历史过长而 {@code prompt_too_long}，就先丢最早一批消息再重试。
 *
 * <p>两处实现取舍：①把待摘要历史<b>渲染成纯文本</b>再发（而不是直接发带 tool_calls 的原始消息），
 * 避免"请求里有工具调用、却没带工具定义"在 OpenAI 兼容服务上触发协议校验；②摘要请求本身
 * <b>不写进会话历史、不落盘</b>（只有摘要产物会被写回内存历史、仍不落 JSONL），和 M5 的提取同款"旁路"思路。
 *
 * <p><b>面试关注：模型摘要的正确性边界。</b>摘要输入是前面几层的输出，不一定仍含全部原始正文；
 * 转成文本又丢掉了消息的结构与来源隔离。这里依赖摘要提示、未提供工具定义来限制行为，
 * 不会校验九段结构、事实完整性或空摘要，历史中的文本也可能干扰摘要模型。
 * 因此它提供的是降低请求规模的兜底策略，不是可逆压缩或事实准确性的证明。
 *
 * <p>先拿到摘要，再替换内存历史；摘要调用异常时不会执行替换，原会话仍在。
 * 只有识别为“上下文过长”的异常才会删去摘要请求前部并重试，其他异常直接向调用方抛出。
 * 紧急重试按消息条数而非 Token 丢弃，老事实可能不再进入摘要；原始日志可供另行查证，
 * 但模型之后并不会自动拥有这些已省略事实。
 */
public class AutoCompactor {

    /** 保留最近几"轮"原文（一轮 = 一条用户消息 + 其后到下一条用户消息前的内容）。 */
    private static final int KEEP_RECENT_TURNS = 2;
    /** 紧急压缩最多丢弃多少批后放弃。 */
    private static final int MAX_EMERGENCY_DROPS = 8;

    private final ModelClient modelClient;
    private final Renderer renderer;

    public AutoCompactor(ModelClient modelClient, Renderer renderer) {
        this.modelClient = modelClient;
        this.renderer = renderer;
    }

    /**
     * 对窗口做一次自动压缩：调模型摘要整段历史，再用"一条摘要消息 + 最近几条原文"组成新窗口返回；
     * 并在 {@code session} 非 {@code null} 时把这份新窗口<b>写回内存历史</b>（不动 JSONL）。
     *
     * <p>是否触发（阈值 / 是否允许调模型）由调用方决定：不想压缩时调用方直接不调本方法。
     *
     * @param session 承载内存历史的会话：非 {@code null} 时把"摘要 + 最近几条原文"通过
     *                {@link Session#replaceHistoryInMemory} 替换掉内存 {@code messages}（JSONL 不动）；
     *                为 {@code null} 时只返回新窗口、不改内存
     */
    public List<Message> apply(List<Message> window, Session session) {
        // 1) 组装"摘要请求"：系统提示词（只许输出文字、固定分段）+ 把待摘要历史渲染成纯文本的多条用户消息。
        //    逐条转成可读文字：既避免发送带 tool_calls 的原始消息引发协议校验，也让紧急压缩能"丢最早一批"。
        List<Message> summaryReq = new ArrayList<>();
        summaryReq.add(new SystemMessage(CompactionPromptText.SUMMARY_PROMPT));
        for (Message m : window) {
            String text = toPlainText(m);
            if (!text.isBlank()) {
                summaryReq.add(new UserMessage(text));
            }
        }

        // 2) 调模型（不带工具，避免它去调工具）；若请求自身过长，走紧急压缩先丢最早一批再重试。
        if (renderer != null) {
            renderer.compactionNote("自动压缩：对历史做一次模型摘要…");
        }
        String summary = callWithEmergencyFallback(summaryReq);

        // 3) 用"一条摘要用户消息 + 最近几条原文"组成新窗口。
        List<Message> compacted = new ArrayList<>();
        compacted.add(new UserMessage("[以下为此前对话的压缩摘要，完整原文在会话日志（JSONL）与 tool-results/ 中]\n" + summary));
        // 最近原文也参与过摘要，此处是有意重复：优先保真近期指令与工具细节，代价是部分预算重叠。
        compacted.addAll(recentOriginals(window));

        // 4) session 非 null 时就地改写内存历史：连同最近几条原文替换掉 messages（就地改写内存里的 REPL 数组）；
        //    刻意不动 JSONL——完整原文仍在会话日志与 tool-results/ 里，/resume 读回全量。
        if (session != null) {
            session.replaceHistoryInMemory(compacted);
        }
        return compacted;
    }

    /**
     * 最近几轮原文：从倒数第 {@link #KEEP_RECENT_TURNS} 条用户消息处起，到窗口末尾的一段
     * （一个干净的用户边界起点，保证其中工具调用与结果成对、不留悬空）。
     */
    private List<Message> recentOriginals(List<Message> window) {
        List<Integer> userIdx = new ArrayList<>();
        for (int i = 0; i < window.size(); i++) {
            if (window.get(i) instanceof UserMessage) {
                userIdx.add(i);
            }
        }
        if (userIdx.isEmpty()) {
            return List.of();
        }
        // 选择用户边界，避免按固定消息条数截断到一批工具往返中间。
        // 这要求输入历史原本合法；摘要和其他合成 UserMessage 也会被当作轮次边界。
        int pick = Math.max(0, userIdx.size() - KEEP_RECENT_TURNS);
        int keepFrom = userIdx.get(pick);
        return new ArrayList<>(window.subList(keepFrom, window.size()));
    }

    /** 把单条消息渲染成一行可读文字（供摘要用，不要求可逆）。 */
    private String toPlainText(Message m) {
        if (m instanceof UserMessage um) {
            return "用户: " + (um.getText() == null ? "" : um.getText());
        }
        if (m instanceof AssistantMessage am) {
            StringBuilder sb = new StringBuilder();
            if (am.getText() != null && !am.getText().isBlank()) {
                sb.append("助手: ").append(am.getText().strip());
            }
            if (am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall call : am.getToolCalls()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append("助手调用工具: ").append(call.name()).append('(').append(call.arguments() == null ? "" : call.arguments()).append(')');
                }
            }
            return sb.toString();
        }
        if (m instanceof ToolResponseMessage trm) {
            StringBuilder sb = new StringBuilder();
            for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("工具结果(").append(resp.name()).append("): ").append(resp.responseData() == null ? "" : resp.responseData());
            }
            return sb.toString();
        }
        return m.getText() == null ? "" : m.getText();
    }

    /**
     * 紧急压缩：自动压缩的摘要请求自身 {@code prompt_too_long} 时，丢掉最早一批消息后重试，直到发得出去。
     *
     * <p>只丢<b>本次摘要请求</b>里的消息（保留系统提示词），M4 的 JSONL 原文仍然完整——紧急压缩同样不碰原文。
     * 正常情况下前四层早把窗口压到 95% 以下，这里极少触发；它是"万一第⑤层自己也卡住、程序不崩"的最后防线。
     */
    private String callWithEmergencyFallback(List<Message> summaryReq) {
        List<Message> req = summaryReq;
        // 上限限制的是模型请求次数；即便最后一次失败后还能继续裁剪，也不会无限重试。
        // 因为输入已经转成纯文本 UserMessage，按条丢弃不会制造悬空 tool_call 协议消息。
        for (int attempt = 0; attempt < MAX_EMERGENCY_DROPS; attempt++) {
            try {
                AssistantMessage result = modelClient.call(req, List.of());   // 不带工具
                return result.getText() == null ? "" : result.getText();
            } catch (RuntimeException e) {
                if (!isPromptTooLong(e)) {
                    throw e;                                                   // 别的错误照常抛
                }
                if (renderer != null) {
                    renderer.warn("历史过长，紧急压缩：丢弃最早一批消息后重试摘要…");
                }
                List<Message> dropped = dropEarliestBatch(req);
                if (dropped.size() == req.size()) {
                    break;                                                     // 没得可丢了
                }
                req = dropped;
            }
        }
        throw new IllegalStateException("紧急压缩后摘要请求仍过长，放弃本次自动压缩。");
    }

    /** 丢最早一批：保留第一条（系统提示词），丢掉其后约 1/4 的转录消息。 */
    private List<Message> dropEarliestBatch(List<Message> req) {
        if (req.size() <= 2) {
            return req;   // 只剩系统提示词 + 1 条，无法再丢
        }
        int bodyCount = req.size() - 1;                    // 系统提示词之外的转录条数
        int drop = Math.max(1, (int) Math.ceil(bodyCount * 0.25));
        List<Message> out = new ArrayList<>();
        out.add(req.get(0));                               // 保留系统提示词
        out.addAll(req.subList(1 + drop, req.size()));     // 丢掉最早的 drop 条
        return out;
    }

    /** 判断异常是否"上下文过长"（匹配常见的 prompt_too_long / context length 文案）。 */
    private boolean isPromptTooLong(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null) {
                // 兼容不同服务的异常包装而遍历 cause；字符串匹配是近似判断，可能把其他 too long 误判为超限。
                String lower = msg.toLowerCase();
                if (lower.contains("prompt_too_long")
                        || lower.contains("prompt is too long")
                        || lower.contains("too long")
                        || lower.contains("maximum context length")
                        || lower.contains("context_length_exceeded")
                        || lower.contains("context length")
                        || lower.contains("reduce the length")) {
                    return true;
                }
            }
        }
        return false;
    }
}
