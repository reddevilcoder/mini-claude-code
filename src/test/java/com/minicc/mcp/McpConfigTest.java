package com.minicc.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigTest {

    @Test
    void loadsServersExpandsEnvAndRejectsPlaintextSecrets(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("mcp.json");
        Files.writeString(f, """
                {"mcpServers": {
                  "ok":  {"command": "node", "args": ["s.js"], "env": {"GITHUB_TOKEN": "${GH}", "MODE": "dev"}},
                  "leak": {"command": "node", "env": {"API_KEY": "sk-123"}},
                  "off": {"command": "node", "enabled": false},
                  "nocmd": {"url": "http://x"}
                }}""");
        McpConfig.Loaded l = McpConfig.load(f, Map.of("GH", "secret")::get);
        assertEquals(1, l.servers().size());
        McpConfig.Server s = l.servers().get(0);
        assertEquals("secret", s.env().get("GITHUB_TOKEN"));
        assertEquals("dev", s.env().get("MODE"));
        assertFalse(s.trustReadOnlyHint());
        assertEquals(2, l.errors().size());
        assertTrue(l.errors().get(0).contains("leak"));
    }

    @Test
    void missingFileIsEmpty(@TempDir Path dir) {
        assertTrue(McpConfig.load(dir.resolve("none.json")).servers().isEmpty());
    }

    @Test
    void sanitizesAndDedupesNames() {
        assertEquals("mcp__a_b__c_d", McpManager.uniqueName("mcp__a.b__c d", Set.of()));
        assertEquals("x_2", McpManager.uniqueName("x", Set.of("x")));
        String longName = "mcp__" + "a".repeat(100);
        String u = McpManager.uniqueName(longName, Set.of(longName.substring(0, 64)));
        assertEquals(64, u.length());
        assertTrue(u.endsWith("_2"));
    }
}
