package com.minicc.tool.hook;

import com.minicc.tool.Tool;
import com.minicc.tool.ToolContext;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

/**
 * 执行前钩子链：按顺序执行已注册的钩子，任一钩子拒绝就整体拒绝。
 *
 * <p>M1/M2 传入空列表，等价于全部放行。M3 往链上注册 {@code ApprovalHook}。
 *
 * <p><b>面试关注：责任链为什么有顺序？</b>链的整体条件是“所有钩子均允许”，但执行具有短路语义，
 * 因而顺序影响用户体验和副作用：PlanModeHook 在 ApprovalHook 前面，计划态禁写时直接拒绝，
 * 无需先让用户批准一项最终不能执行的操作。单个 allow 只表示本钩子不反对，不会绕过后续钩子。
 *
 * <p>钩子是工具调用路径的策略层，并非操作系统隔离。其异常没有在本类捕获；新增钩子需自行确定异常语义，
 * 不能假设抛异常就一定会被转成带原因的拒绝结果。hooks 引用也没有复制，约定构造后不再修改注册列表。
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
