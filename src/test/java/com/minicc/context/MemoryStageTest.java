package com.minicc.context;

import com.minicc.memory.AgentMemory;
import com.minicc.memory.AutoMemory;
import com.minicc.session.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MemoryStage 的单元测试：把 AGENT.md + 记忆行为说明 + MEMORY.md 索引拼成一条系统消息注入。 */
class MemoryStageTest {

    @Test
    void injectsBehavioralGuideEvenWhenEmpty(@TempDir Path project, @TempDir Path state) {
        MemoryStage stage = stage(project, state, state.resolve("memory"));
        List<Message> messages = new ArrayList<>();
        stage.contribute(messages, new Session(project));

        assertEquals(1, messages.size(), "应注入恰好一条消息");
        assertEquals(MessageType.SYSTEM, messages.get(0).getMessageType());
        String text = messages.get(0).getText();
        assertTrue(text.contains("auto memory"), "应含记忆系统行为说明");
        assertTrue(text.contains("记忆的类型"), "应含四种类型说明");
        assertTrue(text.contains("你的 MEMORY.md 目前为空"), "无索引时应有空索引占位文案");
    }

    @Test
    void includesAgentMdAndMemoryIndexWhenPresent(@TempDir Path project, @TempDir Path state) throws IOException {
        Path agent = project.resolve(AgentMemory.PROJECT_DIR).resolve(AgentMemory.AGENT_FILE);
        Files.createDirectories(agent.getParent());
        Files.writeString(agent, "# 项目约定\n- 金额用 BigDecimal。\n");
        Path memoryDir = state.resolve("memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("MEMORY.md"), "- [集成测试约定](feedback_testing.md) — 不要 mock 数据库\n");

        MemoryStage stage = stage(project, state, memoryDir);
        List<Message> messages = new ArrayList<>();
        stage.contribute(messages, new Session(project));

        String text = messages.get(0).getText();
        assertTrue(text.contains("项目约定（AGENT.md）"), "应含 AGENT.md 段落标题");
        assertTrue(text.contains("BigDecimal"), "应含 AGENT.md 正文");
        assertTrue(text.contains("## MEMORY.md"), "应含 MEMORY.md 段落标题");
        assertTrue(text.contains("不要 mock 数据库"), "应含 MEMORY.md 索引内容");
    }

    private static MemoryStage stage(Path project, Path state, Path memoryDir) {
        return new MemoryStage(new AgentMemory(project, state), new AutoMemory(memoryDir));
    }
}
