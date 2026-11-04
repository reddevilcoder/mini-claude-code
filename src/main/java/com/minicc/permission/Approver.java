package com.minicc.permission;

import com.minicc.tool.Tool;
import com.minicc.tool.ToolContext;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * 审批交互抽象（M3）：harness 在执行有副作用的操作前，强制弹卡片问"准不准这么做"。
 *
 * <p>只做审批这一件事（和"模型主动提问"的 {@code ask.QuestionAsker} 分开）。由 CLI 的
 * {@code CliApprover} 实现，底层交给 {@code CardPrompter} 渲染卡片。抽成接口便于 {@code ApprovalHook}
 * 在单元测试里注入假实现，也让将来替换交互形态（如换 GUI）只改实现、不动钩子。
 */
public interface Approver {

    /** 为一次有副作用的工具调用弹出审批卡片，返回用户的选择。 */
    Approval requestApproval(AssistantMessage.ToolCall call, Tool tool, ToolContext ctx);
}
