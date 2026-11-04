package com.minicc.tool.hook;

import com.minicc.tool.Tool;
import com.minicc.tool.ToolContext;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

/**
 * 执行前钩子链：按顺序执行已注册的钩子，任一钩子拒绝就整体拒绝。
 *
 * <p>M1/M2 传入空列表，等价于全部放行。M3 往链上注册 {@code ApprovalHook}。
 */
public class HookChain {

    private final List<PreExecuteHook> hooks;

    public HookChain(List<PreExecuteHook> hooks) {
        this.hooks = hooks;
    }

    /** 依次询问每个钩子；只要有一个拒绝，整体就拒绝，并带回它的拒绝原因。 */
    public PreExecuteHook.Decision beforeExecute(AssistantMessage.ToolCall call, Tool tool, ToolContext ctx) {
        for (PreExecuteHook hook : hooks) {
            PreExecuteHook.Decision decision = hook.beforeExecute(call, tool, ctx);
            if (!decision.allowed()) {
                return decision; // 带回原因，供回填给模型
            }
        }
        return PreExecuteHook.Decision.allow();
    }
}
