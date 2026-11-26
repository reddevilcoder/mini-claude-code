package com.minicc.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP Server 配置（M11），读 {@code ~/.mcc/mcp.json}：
 *
 * <pre>{@code
 * { "mcpServers": {
 *     "github": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-github"],
 *                 "env": { "GITHUB_TOKEN": "${GITHUB_TOKEN}" },
 *                 "timeoutMs": 60000, "trustReadOnlyHint": false, "enabled": true } } }
 * }</pre>
 *
 * <p>安全约束：
 * <ul>
 *   <li><b>只认白名单</b>：只有这里列出的 Server 才会被启动；只读全局配置，不读项目目录下的配置——
 *       否则 clone 一个恶意仓库就能让 mcc 启动任意进程；</li>
 *   <li><b>密钥只能是环境变量引用</b>：env 的值必须形如 {@code ${VAR}}（可夹杂字面量前后缀但必须含引用）
 *       的规则只对“像密钥”的键（含 KEY/TOKEN/SECRET/PASSWORD）强制，违反则该 Server 不加载并给出原因；</li>
 *   <li>{@code trustReadOnlyHint} 默认 false：Server 自称只读不可信，默认一律按写操作走审批。</li>
 * </ul>
 */
public final class McpConfig {

    public record Server(String name, List<String> command, Map<String, String> env,
                         long timeoutMs, boolean trustReadOnlyHint) {
    }

    public record Loaded(List<Server> servers, List<String> errors) {
    }

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL).*");
    public static final long DEFAULT_TIMEOUT_MS = 120_000;

    private McpConfig() {
    }

    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".mcc", "mcp.json");
    }

    public static Loaded load(Path file) {
        return load(file, System::getenv);
    }

    static Loaded load(Path file, Function<String, String> env) {
        List<Server> servers = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) {
            return new Loaded(servers, errors);
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(Files.readString(file));
        } catch (IOException e) {
            errors.add(file + " 解析失败：" + e.getMessage());
            return new Loaded(servers, errors);
        }
        var it = root.path("mcpServers").fields();
        while (it.hasNext()) {
            var e = it.next();
            String name = e.getKey();
            JsonNode s = e.getValue();
            if (!s.path("enabled").asBoolean(true)) {
                continue;
            }
            String command = s.path("command").asText("");
            if (command.isBlank()) {
                errors.add(name + "：缺少 command（目前只支持 stdio Server）");
                continue;
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(command);
            s.path("args").forEach(a -> cmd.add(a.asText()));
            Map<String, String> envMap = new LinkedHashMap<>();
            String err = null;
            var envIt = s.path("env").fields();
            while (envIt.hasNext()) {
                var kv = envIt.next();
                String raw = kv.getValue().asText();
                boolean hasRef = VAR.matcher(raw).find();
                if (SECRET_KEY.matcher(kv.getKey()).matches() && !hasRef) {
                    err = name + "：env." + kv.getKey() + " 看起来是密钥，必须写成 ${环境变量} 引用，不能明文写在配置里";
                    break;
                }
                envMap.put(kv.getKey(), expand(raw, env));
            }
            if (err != null) {
                errors.add(err);
                continue;
            }
            servers.add(new Server(name, cmd, envMap,
                    s.path("timeoutMs").asLong(DEFAULT_TIMEOUT_MS),
                    s.path("trustReadOnlyHint").asBoolean(false)));
        }
        return new Loaded(servers, errors);
    }

    static String expand(String raw, Function<String, String> env) {
        Matcher m = VAR.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = env.apply(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
