package com.minicc.context;

import com.minicc.compact.CompactionManager;
import com.minicc.session.Session;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 组装阶段：会话历史这一段——但在放进去之前先跑一遍 ②–⑤ 压缩投影链。
 *
 * <p>这是 M1 预告的"压缩阶段"的兑现：它<b>替换</b>原来的 {@link HistoryStage}
 * （后者只会 {@code messages.addAll(session.history())}），改为把
 * {@link CompactionManager#compact} 投影出的<b>精简窗口</b>追加进去。
 *
 * <p>不改 {@link SystemPromptStage} / {@link MemoryStage}，也不改 {@link ContextAssembler}——
 * 组装顺序仍是"系统提示词 → 记忆 → 会话历史"，只是"会话历史"这一段现在过了压缩链。
 * 第①层工具结果落盘不在这里（它挂在工具结果入库点，产生时就执行过了）。
 *
 * <p><b>面试关注：压缩的预算边界。</b>本阶段只压缩 {@code session.history()}，
 * 不处理前面已经追加的系统提示词、记忆、技能清单和计划提示，也不计算工具定义及响应预留空间。
 * 因而“历史低于阈值”不等于“整次模型请求一定未超限”。它应替代 {@link HistoryStage} 注册，
 * 同时注册两者会把历史重复追加，既浪费上下文，也重复暴露同一批工具消息。
 */
public class CompactionStage implements AssemblyStage {

    private final CompactionManager compactionManager;

    public CompactionStage(CompactionManager compactionManager) {
        this.compactionManager = compactionManager;
    }

    @Override
    public void contribute(List<Message> messages, Session session) {
        // 把内存历史压缩成"发给模型的窗口"（②–⑤，便宜→贵、链式短路），再追加进消息列表。
        messages.addAll(compactionManager.compact(session.history(), session));
    }
}
