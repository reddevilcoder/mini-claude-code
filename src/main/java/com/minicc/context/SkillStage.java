package com.minicc.context;

import com.minicc.session.Session;
import com.minicc.skill.Skill;
import com.minicc.skill.SkillPromptText;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.List;

/**
 * 组装阶段（M7）：每轮注入「Skills 行为说明 + 技能清单」。
 *
 * <p>它和 M5 的 {@link MemoryStage} 完全同类——都是「稳定的靠前背景知识」，每轮基本不变、位置固定，
 * 有利于前缀缓存。插在 {@link MemoryStage} 之后、{@link CompactionStage} 之前：放在历史之前，模型读历史、
 * 做决策时就已经带着「我手上有这些技能」这个前提了。后续 M8 的计划态提示阶段会插在本阶段之后。
 *
 * <p>注入的清单只含 {@code name}/{@code description}/{@code whenToUse} + {@code SKILL.md} 路径
 * （渐进式披露的第一层）；<b>正文由模型按需 Read</b>，本阶段不碰正文。这是「新增阶段不改既有组装」
 * 的又一次兑现——不改 {@link SystemPromptStage}/{@link MemoryStage}/{@link CompactionStage}，
 * 也不改 {@link ContextAssembler}，只把这一个阶段挂进管线。
 *
 * <p><b>面试关注：渐进式披露如何节省 Token？</b>常驻上下文只提供“能做什么、什么时候用、去哪里读”，
 * 需要的技能才通过 Read 展开正文，代价是多一次读取及模型自行选择技能的决策。
 * 本阶段持有启动时传入的技能清单，并不逐轮重新扫描目录；{@code listingMaxChars} 只限制清单部分，
 * 不含行为说明、其他系统消息和随后按需读取的技能正文，不能把它当作整个技能上下文的总预算。
 */
public class SkillStage implements AssemblyStage {

    private final List<Skill> skills;
    private final int listingMaxChars;   // 清单字符预算（来自配置 skills.listingMaxChars）

    public SkillStage(List<Skill> skills, int listingMaxChars) {
        this.skills = skills;
        this.listingMaxChars = listingMaxChars;
    }

    @Override
    public void contribute(List<Message> messages, Session session) {
        if (skills == null || skills.isEmpty()) {
            return;   // 没有任何技能：什么都不加，不浪费上下文（验收标准 4：无技能时零注入）
        }
        String text = SkillPromptText.behavioralGuide()          // Skills 是什么、怎么用（固定）
                + "\n\n## 可用技能清单\n\n"
                + SkillPromptText.renderListing(skills, listingMaxChars);  // 带预算截断的一行一条
        messages.add(new SystemMessage(text));
    }
}
