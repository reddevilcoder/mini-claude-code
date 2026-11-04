package com.minicc.cli.command;

import com.minicc.cli.Renderer;

import java.util.Collection;

/**
 * {@code /help}：按注册顺序列出当前可用的斜杠命令。
 */
public final class HelpCommand implements SlashCommand {

    private final Collection<SlashCommand> commands;
    private final Renderer renderer;

    public HelpCommand(Collection<SlashCommand> commands, Renderer renderer) {
        this.commands = commands;
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "查看可用命令";
    }

    @Override
    public boolean run(String args) {
        renderer.info("可用命令：");
        for (SlashCommand command : commands) {
            renderer.info("  /" + command.name() + "  —  " + command.description());
        }
        return false;
    }
}
