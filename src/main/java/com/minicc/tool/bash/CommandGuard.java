package com.minicc.tool.bash;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 危险命令护栏：在执行命令前粗筛明显危险的命令，命中直接拒绝。
 *
 * <p>要诚实地说明：<b>这不是安全边界，而是一层方便的防呆</b>
 * （纵深防御的一层）。真正的安全边界是 OS 沙箱（{@link Sandbox}，M2）和人工审批（M3）。
 * 这里用一组启发式正则，不追求穷举。
 */
public final class CommandGuard {

    private static final List<Pattern> DENY = List.of(
            Pattern.compile("\\brm\\s+-[a-z]*r[a-z]*f?\\s+/(?!\\S*/)", Pattern.CASE_INSENSITIVE), // rm -rf /
            Pattern.compile("\\brm\\s+-[a-z]*r[a-z]*f\\s+~", Pattern.CASE_INSENSITIVE),           // rm -rf ~
            Pattern.compile("\\bsudo\\b", Pattern.CASE_INSENSITIVE),                                // 提权
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{.*\\}\\s*;\\s*:"),                               // fork 炸弹 :(){ :|:& };:
            Pattern.compile("\\bmkfs(\\.|\\b)", Pattern.CASE_INSENSITIVE),                          // 格式化
            Pattern.compile("\\bdd\\b.*\\bof=/dev/", Pattern.CASE_INSENSITIVE),                     // 写裸设备
            Pattern.compile("\\b(shutdown|reboot|halt|poweroff)\\b", Pattern.CASE_INSENSITIVE),     // 关机/重启
            Pattern.compile(">\\s*/dev/sd[a-z]"),                                                   // 重定向到磁盘
            Pattern.compile("\\bcurl\\b[^|]*\\|\\s*(sh|bash)\\b", Pattern.CASE_INSENSITIVE),         // 下载管道执行
            Pattern.compile("\\bwget\\b[^|]*\\|\\s*(sh|bash)\\b", Pattern.CASE_INSENSITIVE)
    );

    private CommandGuard() {
    }

    /** 命中任一危险模式即视为危险。 */
    public static boolean isDangerous(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        for (Pattern p : DENY) {
            if (p.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }
}
