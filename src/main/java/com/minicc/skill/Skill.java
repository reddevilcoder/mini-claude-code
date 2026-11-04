package com.minicc.skill;

import java.nio.file.Path;

/**
 * 一个已加载技能的元数据（M7）。
 *
 * <p>只持有「进清单需要的字段」+「正文文件路径」；<b>正文本身不在这里</b>——它由模型按需用
 * Read 工具读取，这就是渐进式披露（清单常驻、正文按需）。它和 M5 的 {@code MEMORY.md} 索引 +
 * 主题文件是同一种思想：这里的 {@code name}/{@code description}/{@code whenToUse} 对应索引里的一行，
 * {@link #skillFile} 对应要按需 Read 的那份主题文件。
 *
 * @param name        技能名（缺省取目录名）
 * @param description 一句话描述（进清单）
 * @param whenToUse   何时该用（进清单；模型据此判断相关性，写得越具体越好）
 * @param skillFile   {@code SKILL.md} 的绝对路径（清单里给出，供模型 Read 取回正文）
 * @param baseDir     技能目录（供正文引用的同目录资料定位，如 {@code checklist.md}）
 */
public record Skill(String name, String description, String whenToUse, Path skillFile, Path baseDir) {
}
