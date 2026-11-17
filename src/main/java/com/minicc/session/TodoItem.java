package com.minicc.session;

/**
 * 一条待办：每条有两种描述形式加一个状态。
 *
 * <p><b>面试关注：record 的作用。</b>固定数据结构自动得到构造器、访问器、equals/hashCode，
 * 在这里配合不可变的 String/枚举，适合充当状态快照元素。但 record 不会自动校验字段，
 * 也不自动保证任意引用类型的深度不可变；合法性仍由创建它的工具负责。
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
