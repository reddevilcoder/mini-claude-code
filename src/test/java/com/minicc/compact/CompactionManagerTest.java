package com.minicc.compact;

import com.minicc.config.MccConfig;
import com.minicc.model.ModelClient;
import com.minicc.session.Session;
import com.minicc.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CompactionManager 的单元测试：链式短路（不越阈值不调模型）与第①层落盘委托。 */
class CompactionManagerTest {

    /** 一个"被调用就报错"的模型边界——用来断言某些场景下绝不会触发模型调用。 */
    private ModelClient throwingModel(MccConfig config) {
        return new ModelClient(null, config) {
            @Override
            public AssistantMessage call(List<Message> messages, Collection<Tool> tools) {
                throw new AssertionError("本场景不应调用模型");
            }
        };
    }

    private CompactionManager manager(Path toolResultsDir, MccConfig config) {
        ToolResultCompactor toolResultCompactor = new ToolResultCompactor(
                toolResultsDir, config.toolResultBudget(), MccConfig.DEFAULT_TOOL_RESULT_CHARS);
        return new CompactionManager(
                toolResultCompactor,
                new SnipCompactor(),
                new MicroCompactor(config),
                new ContextCollapser(config),
                new AutoCompactor(throwingModel(config), null),
                config, null);
    }

    @Test
    void shortCircuitsBelowThresholdWithoutCallingModel(@TempDir Path dir) {
        // 大 contextLimit → 短历史远低于折叠阈值；新会话 → 微压缩不触发 → ③④⑤ 全部 no-op。
        MccConfig config = new MccConfig("m", 40, 200000, "", "u", false, 1000, MccConfig.DEFAULT_STATE_DIR);
        CompactionManager mgr = manager(dir, config);

        Session session = new Session(dir);
        session.appendUserMessage("你好");
        session.appendAssistant(new AssistantMessage("你好，我能帮你做什么？"));

        // 不抛 AssertionError 即证明没调用模型；返回的窗口非空。
        List<Message> window = mgr.compact(session.history(), session);
        assertTrue(window.size() >= 2);
    }

    @Test
    void offloadDelegatesToBudget(@TempDir Path dir) {
        MccConfig config = new MccConfig("m", 40, 200000, "", "u", false, 1000, MccConfig.DEFAULT_STATE_DIR);
        CompactionManager mgr = manager(dir, config);

        String big = "y".repeat(40_000);   // 超过 Bash 的 30000 阈值
        ToolResponseMessage out = mgr.offloadToolResult(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "Bash", big)))
                .build());

        String stored = out.getResponses().get(0).responseData();
        assertTrue(stored.startsWith("<工具结果过大"), "大结果应被落盘并换成引用消息");
        assertFalse(stored.contains(big), "入库内容不应包含完整原文");
    }

    @Test
    void skipsMicrocompactWhenRecentlyActive(@TempDir Path dir) {
        MccConfig config = new MccConfig("m", 40, 200000, "", "u", false, 1000, MccConfig.DEFAULT_STATE_DIR);
        CompactionManager mgr = manager(dir, config);
        mgr.setLastRequestAt(Instant.now());

        Session session = sessionWithBashResults(dir, 8);
        List<Message> window = mgr.compact(session.history(), session);
        assertEquals(0, clearedCount(window), "刚请求过时不应清理可再取结果");
    }

    @Test
    void runsMicrocompactAfterIdle(@TempDir Path dir) {
        MccConfig config = new MccConfig("m", 40, 200000, "", "u", false, 1000, MccConfig.DEFAULT_STATE_DIR);
        CompactionManager mgr = manager(dir, config);
        mgr.setLastRequestAt(Instant.now().minus(61, ChronoUnit.MINUTES));

        Session session = sessionWithBashResults(dir, 8);
        List<Message> window = mgr.compact(session.history(), session);
        assertEquals(3, clearedCount(window), "空闲超过阈值后应清掉最早 3 个（8 个留最近 5 个）");
    }

    /** 一条用户消息 + {@code n} 条 Bash 工具结果。 */
    private static Session sessionWithBashResults(Path dir, int n) {
        Session session = new Session(dir);
        session.appendUserMessage("跑一堆命令");
        for (int i = 0; i < n; i++) {
            session.appendToolResult(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("b" + i, "Bash", "output-" + i)))
                    .build());
        }
        return session;
    }

    private static long clearedCount(List<Message> window) {
        return window.stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> ((ToolResponseMessage) m).getResponses().get(0).responseData())
                .filter(d -> d.equals("[Old tool result content cleared]"))
                .count();
    }
}
