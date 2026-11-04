package com.minicc.context;

import com.minicc.memory.AgentMemory;
import com.minicc.memory.AutoMemory;
import com.minicc.memory.MemoryPromptText;
import com.minicc.session.Session;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.List;

/**
 * 组装阶段：装载长期记忆。紧跟 {@link SystemPromptStage}、排在 {@link HistoryStage} 之前。
 *
 * <p>这是 M1 预告的「记忆加载阶段」的兑现——不改既有阶段，也不改
 * {@link ContextAssembler}，只新增这一个阶段挂进管线。每轮把三块内容拼成一条系统消息注入：
 * <ol>
 *   <li>人写的项目约定 {@code AGENT.md}（{@link AgentMemory} 读项目根，没有就跳过）；</li>
 *   <li>记忆系统行为说明（{@link MemoryPromptText}：四种类型、什么不该存、
 *       两步保存、何时访问、记忆 vs 计划/待办）；</li>
 *   <li>{@code MEMORY.md} 索引全文（{@link AutoMemory#readMemoryIndex()}，按 200 行 / 25KB 截断并附告警）。</li>
 * </ol>
 *
 * <p>为什么放在系统提示词之后、会话历史之前？因为记忆是「稳定的背景知识」，和系统提示词一样靠前、
 * 每轮基本不变，有利于前缀缓存；放在历史之前，模型读历史、做决策时就已经带着项目约定和记忆索引了。
 *
 * <p>实现上 mini-cc 复用组装阶段机制，把记忆作为紧跟系统提示词的一条系统消息注入
 * （而不是塞进系统提示词内部的某个 section）。
 */
public class MemoryStage implements AssemblyStage {

    private final AgentMemory agentMemory;
    private final AutoMemory autoMemory;

    public MemoryStage(AgentMemory agentMemory, AutoMemory autoMemory) {
        this.agentMemory = agentMemory;
        this.autoMemory = autoMemory;
    }

    @Override
    public void contribute(List<Message> messages, Session session) {
        StringBuilder sb = new StringBuilder();

        // 1) 人写的项目约定 AGENT.md（有才加）。
        agentMemory.load().ifPresent(text ->
                sb.append("# 项目约定（AGENT.md）\n\n").append(text).append("\n\n"));

        // 2) 记忆系统行为说明：四类型、什么不该存、两步保存、何时访问、记忆 vs 计划/待办（固定文本）。
        sb.append(MemoryPromptText.behavioralGuide(autoMemory.dir())).append("\n\n");

        // 3) MEMORY.md 索引（渐进式披露的入口；主题文件由模型看着索引按需 Read）。
        autoMemory.readMemoryIndex().ifPresentOrElse(
                index -> sb.append("## MEMORY.md\n\n").append(index),
                () -> sb.append("## MEMORY.md\n\n你的 MEMORY.md 目前为空。新保存的记忆会出现在这里。"));

        messages.add(new SystemMessage(sb.toString()));   // 作为一条系统消息注入
    }
}
