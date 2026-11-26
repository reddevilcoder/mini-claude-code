package com.minicc.cli.command;

import com.minicc.cli.Renderer;
import com.minicc.mcp.McpManager;

/**
 * {@code /mcp}：查看 MCP Server 连接状态与工具；{@code /mcp reconnect <name>} 重连（M11）。
 */
public final class McpCommand implements SlashCommand {

    private final McpManager manager;
    private final Renderer renderer;

    public McpCommand(McpManager manager, Renderer renderer) {
        this.manager = manager;
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public String description() {
        return "查看 MCP Server 状态；/mcp reconnect <name> 重连";
    }

    @Override
    public boolean run(String args) {
        String a = args == null ? "" : args.trim();
        if (a.startsWith("reconnect")) {
            String name = a.substring("reconnect".length()).trim();
            if (name.isEmpty()) {
                manager.servers().forEach(h -> manager.reconnect(h.name()));
            } else if (!manager.reconnect(name)) {
                renderer.warn("没有名为 " + name + " 的 MCP Server。");
                return false;
            }
        }
        if (manager.isEmpty() && manager.configErrors().isEmpty()) {
            renderer.info("未配置 MCP Server。在 ~/.mcc/mcp.json 的 mcpServers 下添加（见 README）。");
            return false;
        }
        StringBuilder sb = new StringBuilder("MCP Server（注意：MCP Server 不在沙箱内运行）\n");
        for (McpManager.ServerHandle h : manager.servers()) {
            sb.append("  ").append(h.name()).append("  ").append(h.status()).append('\n');
            h.toolNames().forEach(t -> sb.append("      - ").append(t).append('\n'));
        }
        manager.configErrors().forEach(e -> sb.append("  ⚠ ").append(e).append('\n'));
        renderer.info(sb.toString().stripTrailing());
        return false;
    }
}
