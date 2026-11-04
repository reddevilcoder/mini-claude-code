package com.minicc.cli.command;

import com.minicc.cli.Renderer;

/**
 * {@code /exit}：结束交互式会话。
 */
public final class ExitCommand implements SlashCommand {

    private final Renderer renderer;

    public ExitCommand(Renderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return "exit";
    }

    @Override
    public String description() {
        return "退出";
    }

    @Override
    public boolean run(String args) {
        renderer.info("再见。");
        return true;
    }
}
