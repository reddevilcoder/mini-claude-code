package com.minicc.compact;

import com.minicc.config.MccConfig;
import com.minicc.tool.ToolCallHelper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第④层上下文折叠的单元测试：不调模型，保留用户请求和末尾结论，较早轮次的工具往返折成结构化 JSON。 */
class ContextCollapserTest {

    /** contextLimit 设得很小，强制触发折叠（阈值 = limit * collapseAt）。 */
    private MccConfig tinyLimitConfig() {
        return new MccConfig("m", 40, 10, "", "u", false, 1000, MccConfig.DEFAULT_STATE_DIR);
    }

    @Test
    void foldsEarlierTurnsPreservingUserMessages() {
        ContextCollapser collapser = new ContextCollapser(tinyLimitConfig());
        List<Message> window = new ArrayList<>();
        // 三轮对话，每轮一个工具往返 + 一条结论。
        window.add(new UserMessage("u1 读文件"));
        window.add(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(ToolCallHelper.of("r1", "Read", Map.of("file_path", "/a"))))
                .build());
        window.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("r1", "Read", "file body")))
                .build());
        window.add(new AssistantMessage("读完了"));

        window.add(new UserMessage("u2 跑命令"));
        window.add(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(ToolCallHelper.of("b1", "Bash", Map.of("command", "ls"))))
                .build());
        window.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("b1", "Bash", "a.txt")))
                .build());
        window.add(new AssistantMessage("跑完了"));

        window.add(new UserMessage("u3 检索"));
        window.add(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(ToolCallHelper.of("g1", "Grep", Map.of("pattern", "x"))))
                .build());
        window.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("g1", "Grep", "hit")))
                .build());
        window.add(new AssistantMessage("查完了"));

        List<Message> out = collapser.apply(window);

        // 1) 三条用户请求全部保留、未截断。
        List<String> users = out.stream()
                .filter(m -> m instanceof UserMessage)
                .map(Message::getText)
                .toList();
        assertEquals(List.of("u1 读文件", "u2 跑命令", "u3 检索"), users, "用户请求应完整保留");

        // 2) 较早轮次的工具往返折成结构化 JSON，末尾结论原文仍在，且紧跟在摘要后面。
        assertTrue(isToolSummary(out.get(1)), "u1 的工具往返应折成结构化 JSON 摘要");
        assertEquals("读完了", out.get(2).getText(), "u1 的末尾结论应原样保留");
        assertTrue(isToolSummary(out.get(4)), "u2 的工具往返应折成结构化 JSON 摘要");
        assertEquals("跑完了", out.get(5).getText(), "u2 的末尾结论应原样保留");
        assertEquals("查完了", out.get(out.size() - 1).getText(), "最近一轮的结论应保留");

        // 3) 被折叠轮次的工具结果原文消失（r1/b1 不再出现）；最近一轮的 g1 仍在。
        assertTrue(hasResponseId(out, "g1"), "最近一轮的工具结果应保留");
        assertTrue(!hasResponseId(out, "r1") && !hasResponseId(out, "b1"), "被折叠轮次的工具结果应消失");
    }

    /** 折掉最早一轮就低于阈值时，应停在这一轮，不再把后面仍然有用的原文也折掉。 */
    @Test
    void foldsOnlyEnoughTurnsToFallUnderThreshold() {
        // collapseAt 默认 0.9，阈值 = 200 * 0.9 = 180。最早一轮的工具结果约 2000 字符，单独就超阈值。
        MccConfig config = new MccConfig("m", 40, 200, "", "u", false, 1000, MccConfig.DEFAULT_STATE_DIR);
        ContextCollapser collapser = new ContextCollapser(config);

        List<Message> window = new ArrayList<>();
        window.add(new UserMessage("u1"));
        window.add(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(ToolCallHelper.of("r1", "Read", Map.of("file_path", "/a"))))
                .build());
        window.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("r1", "Read", "x".repeat(2000))))
                .build());
        window.add(new AssistantMessage("done1"));

        window.add(new UserMessage("u2"));
        window.add(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(ToolCallHelper.of("b1", "Bash", Map.of("command", "ls"))))
                .build());
        window.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("b1", "Bash", "ok")))
                .build());
        window.add(new AssistantMessage("done2"));

        window.add(new UserMessage("u3"));
        window.add(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(ToolCallHelper.of("g1", "Grep", Map.of("pattern", "x"))))
                .build());
        window.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("g1", "Grep", "hit")))
                .build());
        window.add(new AssistantMessage("done3"));

        List<Message> out = collapser.apply(window);

        assertTrue(!hasResponseId(out, "r1"), "超大的最早一轮应被折叠");
        assertEquals("done1", textAfterSummary(out), "被折叠一轮的末尾结论应原样保留");
        assertTrue(hasResponseId(out, "b1"), "折掉一轮后已低于阈值，下一轮应保留原文");
        assertTrue(hasResponseId(out, "g1"), "最近一轮应保留原文");
    }

    private static boolean isToolSummary(Message message) {
        return message instanceof AssistantMessage am
                && am.getText() != null
                && am.getText().contains("结构化摘要")
                && am.getText().contains("\"tool\"")
                && (am.getToolCalls() == null || am.getToolCalls().isEmpty());
    }

    /** 第一条结构化摘要后面紧跟的那条消息文本。 */
    private static String textAfterSummary(List<Message> messages) {
        for (int i = 0; i < messages.size() - 1; i++) {
            if (isToolSummary(messages.get(i))) {
                return messages.get(i + 1).getText();
            }
        }
        return null;
    }

    private boolean hasResponseId(List<Message> messages, String id) {
        for (Message m : messages) {
            if (m instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    if (id.equals(r.id())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
