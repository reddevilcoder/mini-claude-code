package com.minicc.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.minicc.agent.CancelToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 手写的最小 MCP 客户端：JSON-RPC 2.0 请求/响应配对 + initialize 握手 + tools/list + tools/call。
 *
 * <p>面试关注：
 * <ul>
 *   <li>并发请求用自增 id → {@link CompletableFuture} 表配对，读线程收到响应后 complete；</li>
 *   <li>超时与 Ctrl-C 都会发 {@code notifications/cancelled}，让 Server 停下手上的活；</li>
 *   <li>连接断开时所有未决请求立即失败，而不是挂到超时。</li>
 * </ul>
 */
public final class McpClient implements AutoCloseable {

    public static final String PROTOCOL_VERSION = "2025-06-18";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** tools/list 返回的一个工具。 */
    public record ToolInfo(String name, String description, Map<String, Object> inputSchema, boolean readOnlyHint) {
    }

    /** tools/call 结果。 */
    public record CallResult(String text, boolean isError) {
    }

    public static final class McpException extends Exception {
        public McpException(String message) {
            super(message);
        }
    }

    private final Transport transport;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private volatile boolean closed;
    private volatile String serverName = "";

    public McpClient(Transport transport) {
        this.transport = transport;
    }

    /** 启动传输并完成 initialize 握手。 */
    public void connect(long timeoutMs) throws IOException, McpException {
        transport.start(this::onMessage, this::onClose);
        ObjectNode params = JSON.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        ObjectNode info = params.putObject("clientInfo");
        info.put("name", "mini-claude-code");
        info.put("version", "1.0");
        JsonNode result = request("initialize", params, timeoutMs, null);
        serverName = result.path("serverInfo").path("name").asText("");
        notify("notifications/initialized", null);
    }

    public String serverName() {
        return serverName;
    }

    public boolean isAlive() {
        return !closed && transport.isAlive();
    }

    /** 拉取全部工具（处理 nextCursor 分页）。 */
    @SuppressWarnings("unchecked")
    public List<ToolInfo> listTools(long timeoutMs) throws McpException {
        List<ToolInfo> out = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = JSON.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = request("tools/list", params, timeoutMs, null);
            for (JsonNode t : result.path("tools")) {
                Map<String, Object> schema = t.has("inputSchema")
                        ? JSON.convertValue(t.get("inputSchema"), Map.class)
                        : Map.of("type", "object", "properties", Map.of());
                out.add(new ToolInfo(t.path("name").asText(), t.path("description").asText(""),
                        schema, t.path("annotations").path("readOnlyHint").asBoolean(false)));
            }
            cursor = result.hasNonNull("nextCursor") ? result.get("nextCursor").asText() : null;
        } while (cursor != null && !cursor.isEmpty());
        return out;
    }

    /** 调用工具；只拼接 text 类内容，其他类型（image/resource）给出占位说明。 */
    public CallResult callTool(String name, Map<String, Object> arguments, long timeoutMs, CancelToken cancel)
            throws McpException {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", name);
        params.set("arguments", JSON.valueToTree(arguments == null ? Map.of() : arguments));
        JsonNode result = request("tools/call", params, timeoutMs, cancel);
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : result.path("content")) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String type = c.path("type").asText();
            if ("text".equals(type)) {
                sb.append(c.path("text").asText());
            } else if ("resource".equals(type) && c.path("resource").has("text")) {
                sb.append(c.path("resource").path("text").asText());
            } else {
                sb.append("[").append(type).append(" 内容已省略]");
            }
        }
        if (sb.length() == 0 && result.has("structuredContent")) {
            sb.append(result.get("structuredContent").toString());
        }
        return new CallResult(sb.toString(), result.path("isError").asBoolean(false));
    }

    JsonNode request(String method, JsonNode params, long timeoutMs, CancelToken cancel) throws McpException {
        if (closed) {
            throw new McpException("MCP 连接已关闭");
        }
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        ObjectNode msg = JSON.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) {
            msg.set("params", params);
        }
        try (CancelToken.Registration ignored = cancel == null ? null
                : cancel.onCancel(() -> future.completeExceptionally(new McpException("已被用户取消")))) {
            transport.send(JSON.writeValueAsString(msg));
            JsonNode response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (response.has("error")) {
                JsonNode err = response.get("error");
                throw new McpException("MCP 错误 " + err.path("code").asInt() + "：" + err.path("message").asText());
            }
            return response.path("result");
        } catch (TimeoutException e) {
            sendCancelled(id, "timeout");
            throw new McpException(method + " 超时（" + timeoutMs + "ms）");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof McpException me) {
                if (me.getMessage().contains("取消")) {
                    sendCancelled(id, "user cancelled");
                }
                throw me;
            }
            throw new McpException(String.valueOf(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendCancelled(id, "interrupted");
            throw new McpException("被中断");
        } catch (IOException e) {
            throw new McpException("发送失败：" + e.getMessage());
        } finally {
            pending.remove(id);
        }
    }

    private void notify(String method, JsonNode params) throws IOException {
        ObjectNode msg = JSON.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null) {
            msg.set("params", params);
        }
        transport.send(JSON.writeValueAsString(msg));
    }

    private void sendCancelled(long id, String reason) {
        try {
            ObjectNode p = JSON.createObjectNode();
            p.put("requestId", id);
            p.put("reason", reason);
            notify("notifications/cancelled", p);
        } catch (IOException ignore) {
            // 尽力而为。
        }
    }

    private void onMessage(String line) {
        JsonNode node;
        try {
            node = JSON.readTree(line);
        } catch (IOException e) {
            return;   // 非 JSON 行（有些 Server 会往 stdout 打日志）忽略
        }
        if (node.has("id") && (node.has("result") || node.has("error"))) {
            CompletableFuture<JsonNode> f = pending.get(node.get("id").asLong());
            if (f != null) {
                f.complete(node);
            }
        } else if (node.has("id") && node.has("method")) {
            // Server → Client 请求（ping、sampling…）：只支持 ping，其余回 method not found。
            ObjectNode resp = JSON.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.set("id", node.get("id"));
            if ("ping".equals(node.get("method").asText())) {
                resp.putObject("result");
            } else {
                ObjectNode err = resp.putObject("error");
                err.put("code", -32601);
                err.put("message", "Method not found");
            }
            try {
                transport.send(JSON.writeValueAsString(resp));
            } catch (IOException ignore) {
                // ignore
            }
        }
        // 通知（如 tools/list_changed）暂不处理：用户可 /mcp reconnect 刷新。
    }

    private void onClose() {
        closed = true;
        McpException ex = new McpException("MCP Server 已断开");
        pending.values().forEach(f -> f.completeExceptionally(ex));
    }

    @Override
    public void close() {
        closed = true;
        transport.close();
        onClose();
    }
}
