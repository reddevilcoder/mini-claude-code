package com.minicc.mcp;

import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.Map;

/**
 * 把一个 MCP 远端工具适配成本地 {@link Tool}（M11）。对 AgentLoop、钩子链、压缩来说，它与内置工具无异。
 *
 * <p>默认非只读、不可并发：要走审批、Plan Mode 下被拦；只有 Server 配置了 trustReadOnlyHint
 * 且工具自报 readOnlyHint 时才视为只读。
 */
public final class McpTool implements Tool {

    public static final String PREFIX = "mcp__";

    private final String exposedName;
    private final McpManager.ServerHandle server;
    private final McpClient.ToolInfo info;

    public McpTool(String exposedName, McpManager.ServerHandle server, McpClient.ToolInfo info) {
        this.exposedName = exposedName;
        this.server = server;
        this.info = info;
    }

    @Override
    public String name() {
        return exposedName;
    }

    @Override
    public String description() {
        String hint = info.readOnlyHint() ? "（Server 声明只读）" : "";
        return "[MCP:" + server.name() + "] " + info.description() + hint;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return info.inputSchema();
    }

    @Override
    public boolean isReadOnly() {
        return server.config().trustReadOnlyHint() && info.readOnlyHint();
    }

    @Override
    public boolean isConcurrencySafe() {
        return false;
    }

    public String serverName() {
        return server.name();
    }

    public String remoteName() {
        return info.name();
    }

    @Override
    public ToolResult execute(AssistantMessage.ToolCall call, ToolContext ctx) {
        McpClient client = server.client();
        if (client == null || !client.isAlive()) {
            return ToolResult.error("MCP Server " + server.name() + " 未连接；可让用户执行 /mcp reconnect " + server.name());
        }
        try {
            McpClient.CallResult r = client.callTool(info.name(), ToolCallHelper.arguments(call),
                    server.config().timeoutMs(), ctx.cancelToken());
            String text = r.text().isEmpty() ? "(无输出)" : r.text();
            return r.isError() ? ToolResult.error(text) : ToolResult.ok(text);
        } catch (McpClient.McpException e) {
            if (ctx.cancelToken() != null && ctx.cancelToken().isCancelled()) {
                return ToolResult.cancelled();
            }
            return ToolResult.error("MCP 调用失败：" + e.getMessage());
        }
    }
}
