package com.minicc.permission;

/**
 * 用户对审批卡片的选择（M3）。
 *
 * <p>文件/命令审批卡片的三个选项：允许本次 / 本会话内放行同类 / 拒绝并说明原因。
 *
 * @param kind   选择的类别
 * @param reason 当 kind 为 REJECT 时，用户填写的拒绝原因（回填给模型）；其它情况为 null
 */
public record Approval(Kind kind, String reason) {

    /** 三个选项。 */
    public enum Kind {
        /** 允许：只放行当前这一次，下次同类仍会再问。 */
        ALLOW_ONCE,
        /** 允许本会话内的同类操作：本次放行并记入 PermissionState，之后同类不再询问。 */
        ALLOW_SESSION,
        /** 拒绝，并说明原因：不执行，把原因回填给模型让它换做法。 */
        REJECT
    }

    public static Approval allowOnce() {
        return new Approval(Kind.ALLOW_ONCE, null);
    }

    public static Approval allowSession() {
        return new Approval(Kind.ALLOW_SESSION, null);
    }

    public static Approval reject(String reason) {
        return new Approval(Kind.REJECT, reason);
    }
}
