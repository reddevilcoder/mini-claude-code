package com.minicc.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SkillPromptText 的单元测试：行为说明、清单条目格式、单条与整份预算截断。 */
class SkillPromptTextTest {

    private static Skill skill(String name, String desc, String when) {
        return new Skill(name, desc, when, Path.of("/tmp/.mcc/skills/" + name + "/SKILL.md"),
                Path.of("/tmp/.mcc/skills/" + name));
    }

    @Test
    void behavioralGuideStressesMandatoryLoadViaRead() {
        String guide = SkillPromptText.behavioralGuide();
        assertTrue(guide.contains("强制"), "应强调匹配时必须先加载");
        assertTrue(guide.contains("Read"), "应说明用 Read 读取 SKILL.md");
        assertTrue(guide.contains("渐进式披露"), "应点明渐进式披露");
    }

    @Test
    void entryFormatIncludesNameDescWhenAndPath() {
        String listing = SkillPromptText.renderListing(
                List.of(skill("commit-helper", "规范化生成提交信息", "当用户要创建 git 提交时")));
        assertTrue(listing.contains("- commit-helper: 规范化生成提交信息 - 当用户要创建 git 提交时"),
                "应是「- 名字: 描述 - 何时用」格式");
        assertTrue(listing.contains("commit-helper/SKILL.md"), "应附上 SKILL.md 路径");
    }

    @Test
    void entryOmitsDashWhenNoWhenToUse() {
        String listing = SkillPromptText.renderListing(List.of(skill("foo", "描述", "")));
        assertTrue(listing.contains("- foo: 描述（"), "无 when_to_use 时不应出现多余的 - 分隔");
    }

    @Test
    void truncatesOverlongEntryDescription() {
        String longDesc = "x".repeat(500);
        String listing = SkillPromptText.renderListing(List.of(skill("big", longDesc, "")));
        assertTrue(listing.contains("…"), "超长单条应被截断并以省略号结尾");
        assertFalse(listing.contains("x".repeat(300)), "不应保留全部超长内容");
    }

    @Test
    void truncatesWholeListingByBudget() {
        List<Skill> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            many.add(skill("skill-" + i, "一句话描述内容足够长以便撑满预算", "某个使用场景"));
        }
        String listing = SkillPromptText.renderListing(many, 500);  // 很小的预算
        assertTrue(listing.contains("已按预算截断"), "超预算应给出截断提示");
        assertTrue(listing.length() < 1200, "整份清单应被控制在预算量级附近");
    }
}
