package com.minicc.skill;

import java.util.List;

/**
 * Skills 的提示文本（M7）：给模型的「Skills 行为说明」，以及把技能列表渲染成「带预算截断的清单」。
 *
 * <p>行为说明的要点：把「用 Skill 工具调用技能名」改成 mini-cc 的做法「用 Read 读取该技能的 SKILL.md」
 * （mini-cc 不新增调用工具，正文走已有的 Read 按需加载），并强调「匹配到技能必须先加载、绝不只提不加载」。
 *
 * <p>清单格式与预算：单条描述上限 250 字符、整份清单约占 1% 上下文窗口（默认 8000 字符）、
 * 每条写成 {@code - name: description - whenToUse}。mini-cc 取「单条上限 + 整份上限」两道截断的
 * 简化版（和 M5 {@code MEMORY.md} 截断同构），并额外在每条末尾附上 {@code SKILL.md} 的绝对路径，供模型直接 Read。
 */
public final class SkillPromptText {

    private SkillPromptText() {
    }

    /** 单条描述字符上限（250）。 */
    static final int MAX_ENTRY_CHARS = 250;

    /** 整份清单字符上限的缺省值（约占 1% 上下文窗口，8000 字符量级）。 */
    public static final int DEFAULT_LISTING_MAX_CHARS = 8_000;

    /** Skills 行为说明（固定文本）：强调「强制先加载」。 */
    public static String behavioralGuide() {
        return """
                # 技能（Skills）

                你有一批可复用的「技能」——每个技能是一份写好的操作手册，告诉你某一类任务该怎么一步步做。
                下面的清单只列出每个技能的名字、用途和它的 SKILL.md 文件路径（这是渐进式披露：清单常驻，正文按需）。

                如何使用技能：
                - 当用户的请求与某个技能匹配时，这是一条**强制要求**：先用 Read 读取那个技能的 SKILL.md（清单里给了绝对路径），
                  再开始做这项任务——不要只凭清单里的一句话描述就动手。
                - 读完 SKILL.md 后，严格按它正文里的步骤执行；正文若引用了同目录的其它文件，按需再 Read。
                - 绝不要嘴上提到某个技能却不去 Read 它；也不要在明显匹配时跳过技能、自己另起一套做法。
                - 如果没有任何技能匹配当前任务，就按平常的方式正常处理，不必勉强套用技能。""";
    }

    /** 用缺省预算渲染清单。 */
    public static String renderListing(List<Skill> skills) {
        return renderListing(skills, DEFAULT_LISTING_MAX_CHARS);
    }

    /**
     * 把技能列表渲染成「一行一条」的清单，带单条截断与总量截断。
     *
     * @param skills   技能列表
     * @param maxChars 整份清单的字符上限（来自配置 {@code skills.listingMaxChars}）；{@code <=0} 视作不限
     */
    public static String renderListing(List<Skill> skills, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (Skill s : skills) {
            String line = formatEntry(s);
            // 超预算：停止并给出「已截断」提示（/skills 命令仍能看全）。
            if (maxChars > 0 && sb.length() + line.length() + 1 > maxChars) {
                sb.append("> （技能较多，清单已按预算截断——未列出的技能可用 /skills 查看）\n");
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 单条：{@code - 名字: 描述 - 何时用（SKILL.md 绝对路径）}，描述+何时用合起来按单条上限截断。 */
    private static String formatEntry(Skill s) {
        String desc = s.whenToUse().isBlank()
                ? s.description()
                : s.description() + " - " + s.whenToUse();
        if (desc.length() > MAX_ENTRY_CHARS) {
            desc = desc.substring(0, MAX_ENTRY_CHARS - 1) + "…";
        }
        return "- " + s.name() + ": " + desc + "（" + s.skillFile() + "）";
    }
}
