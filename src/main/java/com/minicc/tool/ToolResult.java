package com.minicc.tool;

import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * 工具执行结果：会作为"观察"回填给模型。
 *
 * <p>M1 的工具结果都是文本。{@code error} 为 true 表示这是一条错误观察——
 * 注意错误不会中断控制循环，而是作为普通观察回传，给模型自行纠正的机会
 * （把工具错误当作观察回传）。
 *
 * @param content 结果文本（成功时是内容，失败时是错误说明）
 * @param error   是否为错误结果
 */
public record ToolResult(String content, boolean error) {

    /** 构造一个成功结果。 */
    public static ToolResult ok(String content) {
        return new ToolResult(content, false);
    }

    /** 构造一个错误结果。 */
    public static ToolResult error(String message) {
        return new ToolResult(message, true);
    }

    /** 构造一个"被拒绝"结果（M3 审批拒绝、但未填原因时的兜底）。 */
    public static ToolResult denied(AssistantMessage.ToolCall call) {
        return new ToolResult("用户拒绝执行工具：" + call.name(), true);
    }

    /**
     * 构造一个带原因的"被拒绝"结果（M3 审批拒绝时用）。
     *
     * <p>把用户填写的拒绝原因作为观察回填给模型（"用户拒绝了这次操作，理由：……"），
     * 模型据此换做法或向用户澄清。被拒绝按错误观察回传，但不中断循环。
     */
    public static ToolResult denied(AssistantMessage.ToolCall call, String reason) {
        if (reason == null || reason.isBlank()) {
            return denied(call);
        }
        return new ToolResult("用户拒绝执行工具 " + call.name() + "，理由：" + reason, true);
    }

    /**
     * 构造一个"已取消/未执行"结果（M3 Ctrl-C 取消时用）。
     *
     * <p>取消不是错误，但仍作为观察回填以保持会话历史合法（每个工具调用都要有对应的工具响应，
     * 否则取消后继续对话时请求体会不完整）。标记为非错误，循环会在安全检查点停下。
     */
    public static ToolResult cancelled() {
        return new ToolResult("已被用户中断（Ctrl-C），未执行。", false);
    }
}
