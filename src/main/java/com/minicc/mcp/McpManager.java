package com.minicc.mcp;

import com.minicc.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 管理全部 MCP Server 的连接与工具注册（M11）。
 *
 * <p>启动时逐个连接；单个 Server 失败只记录原因，不影响 mcc 启动。
 * 工具名 = {@code mcp__<server>__<tool>}，清洗为 {@code ^[a-zA-Z0-9_-]{1,64}$}（OpenAI function name 约束），
 * 与已有名字冲突时追加 {@code _2、_3…}。
 */
public final class McpManager implements AutoCloseable {

    static final int CONNECT_TIMEOUT_MS = 30_000;
    static final int MAX_NAME = 64;

    /** 一个 Server 的运行时句柄；reconnect 会替换其中的 client。 */
    public static final class ServerHandle {
        private final McpConfig.Server config;
        private volatile McpClient client;
        private volatile String status = "未连接";
        private final List<String> toolNames = new ArrayList<>();

        ServerHandle(McpConfig.Server config) {
            this.config = config;
        }

        public String name() {
            return config.name();
        }

        public McpConfig.Server config() {
            return config;
        }

        public McpClient client() {
            return client;
        }

        public String status() {
            return client != null && !client.isAlive() && status.startsWith("已连接") ? "已断开" : status;
        }

        public List<String> toolNames() {
            return List.copyOf(toolNames);
        }
    }

    private final Map<String, ServerHandle> servers = new LinkedHashMap<>();
    private final List<String> configErrors;
    private final ToolRegistry registry;
    private final Function<McpConfig.Server, Transport> transportFactory;

    public McpManager(McpConfig.Loaded loaded, ToolRegistry registry, Function<McpConfig.Server, Transport> factory) {
        this.registry = registry;
        this.transportFactory = factory;
        this.configErrors = List.copyOf(loaded.errors());
        loaded.servers().forEach(s -> servers.put(s.name(), new ServerHandle(s)));
    }

    /** stdio 默认工厂；stderr 写到 {@code <stateDir>/logs/mcp-<name>.log}。 */
    public static Function<McpConfig.Server, Transport> stdioFactory(Path workingDir, Path stateDir) {
        return s -> new StdioTransport(s.command(), s.env(), workingDir,
                stateDir.resolve("logs").resolve("mcp-" + sanitize(s.name()) + ".log"));
    }

    public List<String> configErrors() {
        return configErrors;
    }

    public List<ServerHandle> servers() {
        return List.copyOf(servers.values());
    }

    public boolean isEmpty() {
        return servers.isEmpty();
    }

    /** 连接全部 Server（启动时调用）。 */
    public void connectAll() {
        servers.values().forEach(this::connect);
    }

    /** 重连一个 Server：关掉旧连接、注销旧工具、重新握手并注册。 */
    public boolean reconnect(String name) {
        ServerHandle h = servers.get(name);
        if (h == null) {
            return false;
        }
        connect(h);
        return true;
    }

    private synchronized void connect(ServerHandle h) {
        if (h.client != null) {
            h.client.close();
        }
        h.toolNames.forEach(registry::remove);
        h.toolNames.clear();
        McpClient client = new McpClient(transportFactory.apply(h.config));
        try {
            client.connect(CONNECT_TIMEOUT_MS);
            List<McpClient.ToolInfo> tools = client.listTools(CONNECT_TIMEOUT_MS);
            h.client = client;
            Set<String> taken = new HashSet<>();
            registry.all().forEach(t -> taken.add(t.name()));
            for (McpClient.ToolInfo info : tools) {
                String exposed = uniqueName(McpTool.PREFIX + h.name() + "__" + info.name(), taken);
                taken.add(exposed);
                registry.register(new McpTool(exposed, h, info));
                h.toolNames.add(exposed);
            }
            h.status = "已连接（" + tools.size() + " 个工具）";
        } catch (Exception e) {
            client.close();
            h.client = null;
            h.status = "连接失败：" + e.getMessage();
        }
    }

    static String sanitize(String raw) {
        String s = raw.replaceAll("[^a-zA-Z0-9_-]", "_");
        return s.isEmpty() ? "_" : s;
    }

    static String uniqueName(String raw, Set<String> taken) {
        String base = sanitize(raw);
        if (base.length() > MAX_NAME) {
            base = base.substring(0, MAX_NAME);
        }
        String name = base;
        for (int i = 2; taken.contains(name); i++) {
            String suffix = "_" + i;
            name = base.substring(0, Math.min(base.length(), MAX_NAME - suffix.length())) + suffix;
        }
        return name;
    }

    @Override
    public void close() {
        servers.values().forEach(h -> {
            if (h.client != null) {
                h.client.close();
            }
        });
    }
}
