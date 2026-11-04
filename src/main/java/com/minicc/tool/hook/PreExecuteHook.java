package com.minicc.tool.hook;

import com.minicc.tool.Tool;
import com.minicc.tool.ToolContext;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * 执行前钩子：在工具真正执行之前被调用，可以放行或拒绝。
 *
 * <p>这是核心扩展点之一。M1 不挂任何钩子（只读工具直接放行）；
 * M3 挂入审批钩子（{@code permission.ApprovalHook}），M6 挂入 Plan Mode 拦截钩子。
 * 届时只需新增钩子实现，不改控制循环里"执行工具"这段代码。
 */
public interface PreExecuteHook {

    /**
     * 在执行工具前判断是否放行。
     *
     * @param call 即将执行的工具调用
     * @param tool 对应的工具实现
     * @param ctx  工具执行上下文
     * @return 放行或拒绝的决定（拒绝时可携带原因）
     */
    Decision beforeExecute(AssistantMessage.ToolCall call, Tool tool, ToolContext ctx);

    /**
     * 放行决定。
     *
     * <p>M1 原是裸枚举 {@code enum Decision { ALLOW, DENY }}；M3 升级为这个小对象，
     * 使 DENY 能携带"拒绝原因"——审批卡片的"拒绝并说明原因"需要把这段文字作为观察
     * 回填给模型（"用户拒绝了这次修改，理由是……"），模型才能据此换做法。这是 M3 对
     * M1 钩子接口<b>唯一</b>的一处改动，且向后兼容原语义（放行/拒绝仍在，只多了 reason）。
     *
     * @param allowed 是否放行
     * @param reason  拒绝原因（放行时为 null）
     */
    record Decision(boolean allowed, String reason) {

        /** 放行。 */
        public static Decision allow() {
            return new Decision(true, null);
        }

        /** 拒绝，并带回原因（回填给模型）。 */
        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }
}
