package com.minicc.cli.command;

import com.minicc.cli.Renderer;
import com.minicc.skill.Skill;

import java.util.List;

/**
 * {@code /skills}：列出当前会话可用的技能（名字 + 描述 + 何时用 + 来源路径），便于用户查看。
 *
 * <p>和 {@link ToolsCommand} 完全同构（纯展示、不改任何状态），对学习「技能从哪来、有哪些」很有帮助。
 * 简化为只读列出。
 *
 * <p>三种情形各有提示：Skills 被关闭（{@code skills.enabled: false}）、启用但无技能、启用且有技能。
 * 注意即使上下文里的清单因预算被截断，这里仍会把全部技能列全（清单截断只影响发给模型的那份）。
 */
public final class SkillsCommand implements SlashCommand {

    private final List<Skill> skills;
    private final boolean enabled;
    private final Renderer renderer;

    public SkillsCommand(List<Skill> skills, boolean enabled, Renderer renderer) {
        this.skills = skills;
        this.enabled = enabled;
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return "skills";
    }

    @Override
    public String description() {
        return "列出当前可用的技能";
    }

    @Override
    public boolean run(String args) {
        if (!enabled) {
            renderer.info("Skills 已关闭（skills.enabled: false）。");
            return false;
        }
        if (skills == null || skills.isEmpty()) {
            renderer.info("当前没有可用技能，可在 ~/.mcc/skills/ 或 <项目>/.mcc/skills/ 下添加 skill-name/SKILL.md。");
            return false;
        }
        renderer.info("可用技能（" + skills.size() + " 个）：");
        for (Skill s : skills) {
            renderer.info("  " + s.name() + "：" + s.description()
                    + (s.whenToUse().isBlank() ? "" : "（" + s.whenToUse() + "）"));
            renderer.info("    " + s.skillFile());
        }
        return false;
    }
}
