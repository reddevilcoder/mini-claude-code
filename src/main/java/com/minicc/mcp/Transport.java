package com.minicc.mcp;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * JSON-RPC 消息的传输层（M11）。
 *
 * <p>只负责“把一行 JSON 发出去 / 收到一行 JSON 就回调”，不懂 JSON-RPC 语义。
 * 目前只实现 stdio；以后加 Streamable HTTP 只需再实现一个 Transport，{@link McpClient} 不动。
 *
 * <p>面试关注：把协议（JSON-RPC 请求/响应配对）与传输（stdio、HTTP）分层，是 MCP 规范本身的结构。
 */
public interface Transport extends AutoCloseable {

    /** 启动传输并开始把收到的每条消息交给 {@code onMessage}；连接断开时调用 {@code onClose}。 */
    void start(Consumer<String> onMessage, Runnable onClose) throws IOException;

    /** 发送一条完整的 JSON 消息（不含换行）。 */
    void send(String json) throws IOException;

    boolean isAlive();

    @Override
    void close();
}
