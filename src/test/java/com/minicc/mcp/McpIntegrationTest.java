package com.minicc.mcp;

import com.minicc.agent.CancelToken;
import com.minicc.permission.PermissionState;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolRegistry;
import com.minicc.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 用真实子进程（fake_mcp_server.py）跑通 stdio + JSON-RPC 全链路。 */
class McpIntegrationTest {

    private McpManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    private static Path script() throws URISyntaxException {
        return Path.of(McpIntegrationTest.class.getResource("/fake_mcp_server.py").toURI());
    }

    private McpManager start(ToolRegistry registry, Path dir, boolean trust, long timeoutMs) throws Exception {
        McpConfig.Server s = new McpConfig.Server("fake", List.of("python3", script().toString()),
                Map.of(), timeoutMs, trust);
        manager = new McpManager(new McpConfig.Loaded(List.of(s), List.of()), registry,
                McpManager.stdioFactory(dir, dir));
        manager.connectAll();
        return manager;
    }

    private static ToolContext ctx(Path dir, CancelToken token) {
        return new ToolContext(dir, null, null, false, 1000, token, new PermissionState());
    }

    @Test
    void listsPaginatedToolsAndCalls(@TempDir Path dir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        start(registry, dir, false, 5000);
        Set<String> names = registry.all().stream().map(Tool::name).collect(Collectors.toSet());
        assertEquals(Set.of("mcp__fake__echo", "mcp__fake__boom", "mcp__fake__slow", "mcp__fake__bad_name_"), names);

        Tool echo = registry.find("mcp__fake__echo").orElseThrow();
        assertFalse(echo.isReadOnly(), "readOnlyHint 默认不可信");
        assertFalse(echo.isConcurrencySafe());
        ToolResult r = echo.execute(ToolCallHelper.of("1", echo.name(), Map.of("text", "hi")), ctx(dir, new CancelToken()));
        assertEquals("echo:hi", r.content());
        assertFalse(r.error());

        Tool boom = registry.find("mcp__fake__boom").orElseThrow();
        assertTrue(boom.execute(ToolCallHelper.of("2", boom.name(), Map.of()), ctx(dir, new CancelToken())).error());
        assertTrue(Files.exists(dir.resolve("logs")));
    }

    @Test
    void trustReadOnlyHintIsOptIn(@TempDir Path dir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        start(registry, dir, true, 5000);
        assertTrue(registry.find("mcp__fake__echo").orElseThrow().isReadOnly());
        assertFalse(registry.find("mcp__fake__boom").orElseThrow().isReadOnly());
    }

    @Test
    void timeoutAndCancel(@TempDir Path dir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        start(registry, dir, false, 300);
        Tool slow = registry.find("mcp__fake__slow").orElseThrow();
        ToolResult r = slow.execute(ToolCallHelper.of("3", slow.name(), Map.of()), ctx(dir, new CancelToken()));
        assertTrue(r.error());
        assertTrue(r.content().contains("超时"));

        CancelToken token = new CancelToken();
        token.cancel();
        ToolResult c = slow.execute(ToolCallHelper.of("4", slow.name(), Map.of()), ctx(dir, token));
        assertEquals(ToolResult.cancelled(), c);
    }

    @Test
    void reconnectReplacesToolsAndFailureIsReported(@TempDir Path dir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        start(registry, dir, false, 5000);
        assertTrue(manager.reconnect("fake"));
        assertEquals(4, registry.all().size());
        assertTrue(manager.servers().get(0).status().startsWith("已连接"));

        McpConfig.Server bad = new McpConfig.Server("bad", List.of("/no/such/binary"), Map.of(), 1000, false);
        McpManager m2 = new McpManager(new McpConfig.Loaded(List.of(bad), List.of()), new ToolRegistry(),
                McpManager.stdioFactory(dir, dir));
        m2.connectAll();
        assertTrue(m2.servers().get(0).status().startsWith("连接失败"));
        m2.close();
    }

    @Test
    void permissionGrantIsPerTool(@TempDir Path dir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        start(registry, dir, false, 5000);
        Tool echo = registry.find("mcp__fake__echo").orElseThrow();
        Tool boom = registry.find("mcp__fake__boom").orElseThrow();
        PermissionState p = new PermissionState();
        var call = ToolCallHelper.of("1", echo.name(), Map.of());
        assertFalse(p.isGranted(call, echo));
        p.grantSession(call, echo);
        assertTrue(p.isGranted(call, echo));
        assertFalse(p.isGranted(ToolCallHelper.of("2", boom.name(), Map.of()), boom));
    }
}
