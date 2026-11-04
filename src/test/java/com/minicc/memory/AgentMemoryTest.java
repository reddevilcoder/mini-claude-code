package com.minicc.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** AgentMemory 的单元测试：读取项目根与可选全局 AGENT.md，按「全局在前、项目在后」拼接。 */
class AgentMemoryTest {

    @Test
    void emptyWhenNoAgentFile(@TempDir Path project, @TempDir Path state) {
        AgentMemory loader = new AgentMemory(project, state);
        assertTrue(loader.load().isEmpty(), "没有任何 AGENT.md 时应返回空");
    }

    @Test
    void readsProjectAgentFile(@TempDir Path project, @TempDir Path state) throws IOException {
        writeProjectAgent(project, "# 项目约定\n- 金额用 BigDecimal。\n");
        AgentMemory loader = new AgentMemory(project, state);
        Optional<String> text = loader.load();
        assertTrue(text.isPresent());
        assertTrue(text.get().contains("BigDecimal"));
    }

    @Test
    void mergesGlobalBeforeProject(@TempDir Path project, @TempDir Path state) throws IOException {
        Files.writeString(state.resolve("AGENT.md"), "# 全局约定\n- 用中文回答。\n");
        writeProjectAgent(project, "# 项目约定\n- 金额用 BigDecimal。\n");
        AgentMemory loader = new AgentMemory(project, state);
        String text = loader.load().orElseThrow();
        int globalPos = text.indexOf("全局约定");
        int projectPos = text.indexOf("项目约定");
        assertTrue(globalPos >= 0 && projectPos >= 0);
        assertTrue(globalPos < projectPos, "全局约定应排在项目约定之前");
    }

    /** 往项目级 {@code <项目>/.mcc/AGENT.md} 写入内容（先建好 .mcc 目录）。 */
    private static void writeProjectAgent(Path project, String content) throws IOException {
        Path agent = project.resolve(AgentMemory.PROJECT_DIR).resolve(AgentMemory.AGENT_FILE);
        Files.createDirectories(agent.getParent());
        Files.writeString(agent, content);
    }
}
