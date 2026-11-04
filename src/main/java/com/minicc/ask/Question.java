package com.minicc.ask;

import java.util.List;

/**
 * AskUserQuestion 的一个问题（M3）。
 *
 * <p>已简化：去掉 preview、per-question notes 等富交互。
 *
 * @param header      很短的标签（≤12 字符），如"加密方案"
 * @param question    完整问题文本，清晰具体、以问号结尾
 * @param options     候选项（2–4 项），不含"其他"——"其他"由系统在渲染时自动补充
 * @param multiSelect 是否允许多选
 */
public record Question(String header, String question, List<Option> options, boolean multiSelect) {

    /**
     * 一个候选项。
     *
     * @param label       1–5 词的简短选项（推荐项可在末尾标注 "(Recommended)"）
     * @param description 这个选项意味着什么、有什么取舍
     */
    public record Option(String label, String description) {
    }
}
