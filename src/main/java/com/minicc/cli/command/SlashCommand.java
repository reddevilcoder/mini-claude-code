package com.minicc.cli.command;

/**
 * 斜杠命令抽象：以 / 开头、用于控制会话本身的命令（如 /help、/tools、/clear、/exit）。
 *
 * <p>和自然语言输入不同，斜杠命令不交给模型处理，而是由 Repl 直接执行。
 */
public interface SlashCommand {

    /** 命令名，不含前导斜杠（如 "help"）。 */
    String name();

    /** 一句话说明，供 /help 展示。 */
    String description();

    /**
     * 执行命令。
     *
     * @param args 命令后面的参数文本（可能为空）
     * @return true 表示要退出程序
     */
    boolean run(String args);
}
