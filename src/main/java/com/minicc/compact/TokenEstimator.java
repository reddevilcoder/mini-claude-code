package com.minicc.compact;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

/**
 * 粗略的 Token 估算：按字符数折算，不接真实分词器（相比真实计数大幅简化）。
 *
 * <p>用经验系数把字符数折成 Token，服务于压缩阈值判断和 {@code /context} 的量级展示。
 * 英文、中文与代码的分词密度不同，这里统一取约 3.5 字符/Token，仅是教学估算，
 * 90% / 95% 的阈值余量不能保证覆盖误差，尤其中文占比较高时可能明显低估。
 *
 * <p>简化：真实计数要用模型对应的 tokenizer，还要算上角色标记、工具 schema 的结构开销；
 * mini-cc 只按字符折算，并在 {@code /context} 里标注"估算值"。方法都是静态的，随取随用。
 *
 * <p><b>面试关注：为什么字符数不等于 Token 数？</b>Token 取决于模型分词器；
 * Java 的 {@code String.length()} 统计 UTF-16 代码单元，也不等于 Unicode 字符数或 UTF-8 字节数。
 * 本实现不计消息协议开销、工具定义、调用 id、助手 metadata 中的 reasoningContent 或输出预留。
 * 要做严格预算，应对最终请求及工具 schema 使用相应分词器，再预留输出和误差空间；
 * 当前估算器适合触发策略，不适合承诺请求一定能够被服务端接受。
 */
public final class TokenEstimator {

    /** 经验系数：平均多少字符折算成一个 Token。 */
    private static final double CHARS_PER_TOKEN = 3.5;

    private TokenEstimator() {
    }

    /** 估算一段文本的 Token 数（向上取整）。 */
    public static int ofText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /**
     * 估算一条消息的 Token 数：累加文本、工具调用的参数 JSON、工具结果文本的字符。
     * 三种消息（用户 / 助手含工具调用 / 工具结果）都覆盖到。
     */
    public static int ofMessage(Message message) {
        if (message == null) {
            return 0;
        }
        int chars = 0;
        if (message instanceof ToolResponseMessage trm) {
            // 工具结果：累加每条 responseData（大结果在入库时已是"预览+路径"，这里估的就是瘦身后的量）。
            for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                if (resp.responseData() != null) {
                    chars += resp.responseData().length();
                }
                if (resp.name() != null) {
                    chars += resp.name().length();
                }
            }
        } else if (message instanceof AssistantMessage am) {
            // 助手消息：正文 + 每个工具调用的名字与参数 JSON。
            if (am.getText() != null) {
                chars += am.getText().length();
            }
            if (am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall call : am.getToolCalls()) {
                    if (call.name() != null) {
                        chars += call.name().length();
                    }
                    if (call.arguments() != null) {
                        chars += call.arguments().length();
                    }
                }
            }
        } else {
            // 其余（用户消息、系统消息）：按正文文本计。
            String text = message.getText();
            if (text != null) {
                chars += text.length();
            }
        }
        return (int) Math.ceil(chars / CHARS_PER_TOKEN);
    }

    /** 估算一个消息列表的总 Token。 */
    public static int ofMessages(List<Message> messages) {
        if (messages == null) {
            return 0;
        }
        int sum = 0;
        for (Message message : messages) {
            sum += ofMessage(message);
        }
        return sum;
    }
}
