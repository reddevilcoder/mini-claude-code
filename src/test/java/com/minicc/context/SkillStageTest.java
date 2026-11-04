package com.minicc.context;

import com.minicc.session.Session;
import com.minicc.skill.Skill;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SkillStage 的单元测试：有技能时注入「行为说明 + 清单」，无技能时零注入。 */
class SkillStageTest {

    private static Skill skill(String name) {
        return new Skill(name, name + " 描述", "何时用 " + name,
                Path.of("/tmp/.mcc/skills/" + name + "/SKILL.md"),
                Path.of("/tmp/.mcc/skills/" + name));
    }

    @Test
    void injectsGuideAndListingWhenSkillsPresent(@TempDir Path project) {
        SkillStage stage = new SkillStage(List.of(skill("commit-helper")), 8000);
        List<Message> messages = new ArrayList<>();
        stage.contribute(messages, new Session(project));

        assertEquals(1, messages.size(), "有技能应注入恰好一条系统消息");
        assertEquals(MessageType.SYSTEM, messages.get(0).getMessageType());
        String text = messages.get(0).getText();
        assertTrue(text.contains("# 技能（Skills）"), "应含行为说明标题");
        assertTrue(text.contains("## 可用技能清单"), "应含清单标题");
        assertTrue(text.contains("- commit-helper:"), "应含技能清单条目");
        assertTrue(text.contains("SKILL.md"), "清单应给出 SKILL.md 路径（供模型按需 Read）");
    }

    @Test
    void injectsNothingWhenNoSkills(@TempDir Path project) {
        SkillStage stage = new SkillStage(List.of(), 8000);
        List<Message> messages = new ArrayList<>();
        stage.contribute(messages, new Session(project));
        assertTrue(messages.isEmpty(), "无技能时不应往上下文加任何东西（验收标准 4）");
    }

    @Test
    void doesNotInjectSkillBody(@TempDir Path project) {
        // 渐进式披露：清单里只有 frontmatter（name/desc/when）+ 路径，绝不含正文。
        SkillStage stage = new SkillStage(List.of(skill("foo")), 8000);
        List<Message> messages = new ArrayList<>();
        stage.contribute(messages, new Session(project));
        String text = messages.get(0).getText();
        assertTrue(text.contains("- foo: foo 描述 - 何时用 foo"), "只注入 frontmatter 清单");
    }
}
