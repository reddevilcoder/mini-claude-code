package com.minicc.ask;

import java.util.List;

/**
 * 提问交互抽象（M3）：由模型主动发起的澄清提问。
 *
 * <p>和审批（{@code permission.Approver}）是两件不同的事：审批是 harness 强制发起的权限判断
 * （准不准这么做），提问是<b>模型主动</b>调用 {@code AskUserQuestionTool} 发起的澄清（要不要/怎么做）。
 * 两者各有各的接口、各有各的 CLI 实现，只共用底层的 {@code CardPrompter} 渲染卡片。
 *
 * <p>抽成接口便于单元测试注入假实现，也让将来替换交互形态（如换 GUI）只改实现、不动工具。
 */
public interface QuestionAsker {

    /** 为模型发起的一组问题弹出选择卡片（自动补"其他"项），返回用户的回答。 */
    List<Answer> askQuestions(List<Question> questions);
}
