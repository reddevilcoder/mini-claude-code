package com.minicc.ask;

import java.util.List;

/**
 * 用户对一个 {@link Question} 的回答（M3）。
 *
 * @param header   对应问题的短标签
 * @param question 对应的问题文本
 * @param choices  用户选定的内容：候选项的 label，或"其他"里用户自己补充的文字
 */
public record Answer(String header, String question, List<String> choices) {
}
