package com.minicc.cli.command;

import com.minicc.cli.Renderer;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolRegistry;

/**
 * {@code /tools}：列出当前已注册的工具，并标明只读还是写操作。
 */
public final class ToolsCommand implements SlashCommand {

    private final ToolRegistry registry;
    private final Renderer renderer;

    public ToolsCommand(ToolRegistry registry, Renderer renderer) {
        this.registry = registry;
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return "tools";
    }

    @Override
    public String description() {
        return "列出当前可用的工具";
    }

    @Override
    public boolean run(String args) {
        renderer.info("已注册工具：");
        for (Tool tool : registry.all()) {
            String kind = tool.isReadOnly() ? "只读" : "写操作";
            renderer.info("  " + tool.name() + "（" + kind + "）");
        }
        return false;
    }
}
