package com.minicc.tool;

import com.minicc.tool.builtin.GlobTool;
import com.minicc.tool.builtin.GrepTool;
import com.minicc.tool.builtin.ReadTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ToolRegistry 的单元测试。 */
class ToolRegistryTest {

    @Test
    void registersAndFindsTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadTool());
        registry.register(new GrepTool());
        registry.register(new GlobTool());

        assertEquals(3, registry.all().size());
        assertTrue(registry.find("Read").isPresent());
        assertTrue(registry.find("Grep").isPresent());
        assertTrue(registry.find("Glob").isPresent());
        assertTrue(registry.find("Nope").isEmpty());
    }

    @Test
    void builtinToolsAreReadOnly() {
        assertTrue(new ReadTool().isReadOnly());
        assertTrue(new GrepTool().isReadOnly());
        assertTrue(new GlobTool().isReadOnly());
    }
}
