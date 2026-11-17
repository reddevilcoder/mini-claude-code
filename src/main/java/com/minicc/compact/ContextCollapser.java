package com.minicc.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicc.config.MccConfig;
import com.minicc.tool.ToolCallHelper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第④层：上下文折叠——它是对完整历史的"读时投影"：摘要只用于当次请求、不写回历史数组，
 * 正是 M6 投影模型的体现。
 *
 * <p><b>针对对象：③微压缩的输出</b>（链式）。<b>作用：❌不改内存历史、❌不改 JSONL——临时映射</b>。
 *
 * <p>窗口在前三层之后仍 ≥ 90%（{@code collapseAt}）时触发。较早轮次保留用户请求和末尾结论，
 * 只把中间的工具往返折成<b>结构化 JSON 摘要</b>，
 * 关键特点是<b>不调用模型</b>——纯用代码从消息元数据里抽取（工具名、成功/失败/空、文件路径与操作类型、
 * 命令、以及若第①层落过盘的落盘路径），几乎零成本、也不会引入模型幻觉。
 *
 * <p>本层的一条铁律：<b>用户消息是意图的锚点，不截断、不丢弃</b>（第⑤层则仅保证近期原文保留，旧消息依赖摘要）。末尾那条
 * <b>不含工具调用的 assistant 结论</b>同样保留原文——工具元数据只能复原「做了什么」，复原不了判断和回答。
 * 所以折叠每一轮时：<b>完整保留该轮的用户请求</b>，只把中间的<b>工具往返</b>（assistant 的工具调用 + 对应工具结果）
 * 折成一条不含工具调用的 assistant 摘要，再把原来的结论 assistant 原样接在摘要后面。
 * 没有工具往返的轮次不插空摘要。一轮里没有结论（停在工具结果上）时，只保留用户消息和摘要。
 *
 * <p>折叠要抽取哪些字段，mini-cc 的取舍是：只保留<b>工具元数据 + 用户请求与末尾结论</b>
 * （工具名、成功/失败/空、文件路径与操作类型、命令、以及若第①层落过盘的落盘路径），
 * 不保留任何原始结果正文。
 *
 * <p><b>面试关注：为什么选整轮边界，又为什么保留最近一轮？</b>同轮助手调用与工具结果一起被替换，
 * 旧调用不再以协议消息出现，避免只删一侧导致请求校验失败；最近一轮仍可能在执行工具，
 * 保留原文能给下一次决策提供细节。按用户消息切轮依赖输入历史本身合法，并不是通用的历史修复器。
 *
 * <p>代码提取不会凭空生成新事实，但信息仍会丢失：中间的助手解释、工具正文和部分参数不会保留。
 * {@code statusOf} 仅凭文本识别空、拒绝、落盘，其余都标成 ok，不能据此断言命令或写入真的成功。
 * 结构化摘要对很短的往返还可能比原文更长；本实现没有“只有缩小才采用”的比较分支，
 * 只根据累计估算在轮次边界停止，因此“折叠”也不是体积单调下降的保证。
 */
public class ContextCollapser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 折叠后那条 assistant 摘要的固定前缀（估算 token 与真正输出必须用同一段文字）。 */
    private static final String SUMMARY_HEADER = "[本轮工具往返的结构化摘要（代码折叠，非模型生成）]\n";

    private final MccConfig config;

    public ContextCollapser(MccConfig config) {
        this.config = config;
    }

    /**
     * 对窗口做一次折叠投影：从开头逐轮折叠，直到估算 token 低于折叠阈值。
     * 折叠边界只落在"一轮结束"处，保证被折叠段里工具调用与结果成对消失、不留悬空（成对铁律）。
     * 最近一轮始终保留原文。
     */
    public List<Message> apply(List<Message> window) {
        int total = TokenEstimator.ofMessages(window);
        double threshold = config.contextLimit() * config.collapseAt();
        if (total < threshold) {
            return window;
        }

        List<Message> out = new ArrayList<>();
        int i = 0;
        int n = window.size();
        // 第一条用户消息之前的内容（极少见）原样留在最前面，不参与折叠。
        while (i < n && !(window.get(i) instanceof UserMessage)) {
            out.add(window.get(i));
            i++;
        }

        boolean folded = false;
        while (i < n) {
            // 一轮 = 这条用户消息 + 其后直到下一条用户消息之前的全部消息。
            int nextUser = i + 1;
            while (nextUser < n && !(window.get(nextUser) instanceof UserMessage)) {
                nextUser++;
            }
            // 后面没有下一轮了：这是最近一轮，保留原文，并把剩余消息原样接上。
            if (nextUser == n) {
                if (!folded) {
                    return window;
                }
                out.addAll(window.subList(i, n));
                return out;
            }

            UserMessage user = (UserMessage) window.get(i);
            List<Message> body = window.subList(i + 1, nextUser);
            // 末尾那条不含工具调用的 assistant 是本轮结论，原文保留；它前面的才是要折的工具往返。
            AssistantMessage conclusion = null;
            if (!body.isEmpty()) {
                Message last = body.get(body.size() - 1);
                if (last instanceof AssistantMessage am && !hasToolCalls(am)) {
                    conclusion = am;
                }
            }
            List<Message> toolCallMessages = conclusion == null ? body : body.subList(0, body.size() - 1);
            if (!containsToolCall(toolCallMessages)) {
                out.add(user);
                out.addAll(body);
                i = nextUser;
                continue;
            }

            int raw = tokensOf(user, body);
            AssistantMessage summary = summaryMessages(toolCallMessages);
            int foldedTokens = TokenEstimator.ofMessage(user) + TokenEstimator.ofMessage(summary);
            if (conclusion != null) {
                foldedTokens += TokenEstimator.ofMessage(conclusion);
            }

            out.add(user);                                  // 用户请求：完整保留（意图锚点，不截断）
            out.add(summary);                               // 工具往返 → 一条 assistant 摘要（无 toolCalls）
            if (conclusion != null) {
                out.add(conclusion);                        // 末尾结论：原文保留
            }
            folded = true;
            total -= raw - foldedTokens;                   // 估算净节省；摘要比原文长时差值也可能为负
            if (total < threshold) {
                out.addAll(window.subList(nextUser, n));    // 后面的轮次不再折，原样接上
                return out;
            }
            i = nextUser;
        }
        return folded ? out : window;
    }

    /** 这段里是否有真正的工具调用。没有的话折叠省不下 token，这一轮保持原文。 */
    private static boolean containsToolCall(List<Message> messages) {
        for (Message message : messages) {
            if (message instanceof AssistantMessage am && hasToolCalls(am)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasToolCalls(AssistantMessage message) {
        return message.getToolCalls() != null && !message.getToolCalls().isEmpty();
    }

    /** 一轮保持原文时的 token：用户消息 + 其后的工具往返与结论。 */
    private static int tokensOf(UserMessage user, List<Message> body) {
        int tokens = TokenEstimator.ofMessage(user);
        for (Message message : body) {
            tokens += TokenEstimator.ofMessage(message);
        }
        return tokens;
    }

    /**
     * 把一轮的工具往返折成一条不含工具调用的 assistant 摘要：逐个工具调用抽取元数据
     * （工具名、文件路径/命令、成功/失败/空、落盘路径），不含任何原始结果正文。
     */
    private AssistantMessage summaryMessages(List<Message> toolCallMessages) {
        // 先建 callId → 结果文本 的映射，便于给每个调用配上它的结果状态。
        Map<String, String> resultById = new LinkedHashMap<>();
        for (Message m : toolCallMessages) {
            if (m instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                    resultById.put(resp.id(), resp.responseData() == null ? "" : resp.responseData());
                }
            }
        }

        List<Map<String, Object>> tools = new ArrayList<>();
        for (Message m : toolCallMessages) {
            if (m instanceof AssistantMessage am && am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall call : am.getToolCalls()) {
                    tools.add(summaryToolCall(call, resultById.get(call.id())));
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tools", tools);
        String json;
        try {
            json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
        } catch (Exception e) {
            // JSON 由我们自己构造，正常不会失败；兜底给个可读的降级文本。
            json = "{\"tools\":" + tools.size() + "}";
        }
        return AssistantMessage.builder().content(SUMMARY_HEADER + json).build();
    }

    /** 从一个工具调用 + 它的结果里抽取折叠字段（文件/命令、状态、落盘路径）。 */
    private Map<String, Object> summaryToolCall(AssistantMessage.ToolCall call, String result) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("tool", call.name());

        // 按工具类型抽取"操作对象"：文件类取 file_path、Bash 取 command。
        switch (call.name()) {
            case "Read", "Edit", "Write" -> {
                String fp = ToolCallHelper.getString(call, "file_path", "");
                if (!fp.isBlank()) {
                    entry.put("file", fp);
                }
            }
            case "Bash" -> {
                String cmd = ToolCallHelper.getString(call, "command", "");
                if (!cmd.isBlank()) {
                    entry.put("command", cmd.length() > 200 ? cmd.substring(0, 200) + "…" : cmd);
                }
            }
            case "Grep", "Glob" -> {
                String pattern = ToolCallHelper.getString(call, "pattern", "");
                if (!pattern.isBlank()) {
                    entry.put("pattern", pattern);
                }
            }
            default -> {
                // 其余工具只记名字。
            }
        }

        // 结果状态：空 / 被拒 / 已落盘 / 一般完成。
        entry.put("status", statusOf(result));
        if (result != null && result.startsWith("<工具结果过大")) {
            String path = extractOffloadPath(result);
            if (path != null) {
                entry.put("offloaded", path);               // 落盘路径（模型需要原文时可据此 Read）
            }
        }
        return entry;
    }

    /** 粗略判断结果状态，供折叠摘要标注。 */
    private String statusOf(String result) {
        if (result == null || result.isBlank()) {
            return "empty";
        }
        String t = result.strip();
        if (t.equals("没有匹配结果。") || t.equals("没有匹配的文件。")) {
            return "empty";
        }
        if (t.startsWith("用户拒绝执行工具")) {
            return "denied";
        }
        if (t.startsWith("<工具结果过大")) {
            return "offloaded";
        }
        // 这里只表示“未匹配上述特殊文本”，没有 ToolResult.error 标志可供判断；错误正文也可能落到 ok。
        return "ok";
    }

    /** 从"引用消息"里解析出落盘文件路径（位于"已落盘："之后、"（共"之前）。 */
    private String extractOffloadPath(String reference) {
        int start = reference.indexOf("已落盘：");
        if (start < 0) {
            return null;
        }
        start += "已落盘：".length();
        int end = reference.indexOf("（共", start);
        if (end < 0) {
            end = reference.indexOf('\n', start);
        }
        if (end < 0) {
            end = reference.length();
        }
        return reference.substring(start, end).trim();
    }
}
