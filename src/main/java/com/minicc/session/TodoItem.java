package com.minicc.session;

/**
 * 一条待办：每条有两种描述形式加一个状态。
 *
 * @param content    祈使形描述，"该做什么"（如 "运行测试"）
 * @param activeForm 进行时描述，执行时展示（如 "正在运行测试"）
 * @param status     状态：待开始 / 进行中 / 已完成
 */
public record TodoItem(String content, String activeForm, Status status) {

    /** 待办状态。 */
    public enum Status {
        PENDING, IN_PROGRESS, COMPLETED;

        /** 从字符串解析（大小写不敏感，兼容 in_progress / in-progress）。 */
        public static Status from(String s) {
            if (s == null) {
                return PENDING;
            }
            String v = s.trim().toUpperCase().replace('-', '_');
            return switch (v) {
                case "IN_PROGRESS" -> IN_PROGRESS;
                case "COMPLETED" -> COMPLETED;
                default -> PENDING;
            };
        }
    }
}
