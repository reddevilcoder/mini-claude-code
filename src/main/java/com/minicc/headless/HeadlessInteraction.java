package com.minicc.headless;

import com.minicc.ask.Answer;
import com.minicc.ask.Question;
import com.minicc.ask.QuestionAsker;
import com.minicc.permission.Approval;
import com.minicc.permission.Approver;
import com.minicc.plan.PlanApprover;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolContext;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

/**
 * Headless 模式下的"无人值守"交互实现：审批、提问、计划确认都不能阻塞等终端输入。
 *
 * <p>三个接口由同一个类实现，策略集中在一处，便于审查：
 * <ul>
 *   <li>{@link PermissionMode#READONLY}：一切写操作直接拒绝（默认，最安全）；</li>
 *   <li>{@link PermissionMode#AUTO}：写操作全部放行——必须配合 fail-closed 沙箱使用（见 MccApplication）。</li>
 * </ul>
 * 提问一律返回"未作答"，让模型自行判断；不允许进入 Plan Mode（无人能批准计划），
 * 若模型已在计划态则直接批准退出，避免任务卡死在只读状态。
 */
public class HeadlessInteraction implements Approver, QuestionAsker, PlanApprover {

    public enum PermissionMode { READONLY, AUTO }

    private final PermissionMode mode;

    public HeadlessInteraction(PermissionMode mode) {
        this.mode = mode;
    }

    @Override
    public Approval requestApproval(AssistantMessage.ToolCall call, Tool tool, ToolContext ctx) {
        if (mode == PermissionMode.AUTO) {
            return Approval.allowOnce();
        }
        return Approval.reject("非交互只读模式（--permission readonly），不允许执行写操作；请只读调研后直接给出答复。");
    }

    @Override
    public List<Answer> askQuestions(List<Question> questions) {
        return List.of();
    }

    @Override
    public boolean confirmEnterPlan() {
        return false;
    }

    @Override
    public Result confirmPlan() {
        return new Result(true, null);
    }
}
