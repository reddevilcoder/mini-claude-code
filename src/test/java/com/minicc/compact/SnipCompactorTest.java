package com.minicc.compact;

import com.minicc.persistence.SessionStore;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第②层 Snip 的单元测试：成对删除空结果 / 被拒绝的调用，窗口对模型仍合法。 */
class SnipCompactorTest {

    private final SnipCompactor snip = new SnipCompactor();

    @Test
    void removesEmptyGrepTurnInPairs() {
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("找一下 Foo"));
        history.add(AssistantMessage.builder()
                .content("检索")
                .toolCalls(List.of(ToolCallHelper.of("g1", "Grep", Map.of("pattern", "Foo"))))
                .build());
        history.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("g1", "Grep", "没有匹配结果。")))
                .build());
        history.add(new AssistantMessage("没找到，建议换关键字。"));

        List<Message> out = snip.apply(history);

        // 空结果那条工具结果被删，对应的工具调用也被摘掉——窗口里不再出现 g1。
        assertFalse(mentions(out, "g1"), "空结果 turn 应被成对删除");
        assertNoDanglingToolCalls(out);
        // 用户消息与最终答复保留。
        assertTrue(out.stream().anyMatch(m -> m instanceof UserMessage));
    }

    @Test
    void removesDeniedCall() {
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("改个文件"));
        history.add(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(ToolCallHelper.of("w1", "Write", Map.of("file_path", "/tmp/a"))))
                .build());
        history.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "w1", "Write", "用户拒绝执行工具 Write，理由：先别动这个文件")))
                .build());

        List<Message> out = snip.apply(history);
        assertFalse(mentions(out, "w1"), "被拒绝的调用应被删除");
        assertNoDanglingToolCalls(out);
    }

    @Test
    void keepsReasoningWhenDroppingOneOfSeveralCalls() {
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("找一下再读"));
        history.add(AssistantMessage.builder()
                .content("检索")
                .properties(Map.of(SessionStore.REASONING_CONTENT, "先搜再读"))
                .toolCalls(List.of(
                        ToolCallHelper.of("g1", "Grep", Map.of("pattern", "Foo")),
                        ToolCallHelper.of("r1", "Read", Map.of("file_path", "/a"))))
                .build());
        history.add(ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponseMessage.ToolResponse("g1", "Grep", "没有匹配结果。"),
                        new ToolResponseMessage.ToolResponse("r1", "Read", "file body")))
                .build());

        List<Message> out = snip.apply(history);

        AssistantMessage assistant = out.stream()
                .filter(m -> m instanceof AssistantMessage)
                .map(m -> (AssistantMessage) m)
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("r1"), assistant.getToolCalls().stream().map(AssistantMessage.ToolCall::id).toList());
        assertEquals("先搜再读", assistant.getMetadata().get(SessionStore.REASONING_CONTENT));
    }

    @Test
    void keepsNonEmptyResults() {
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("找一下 Bar"));
        history.add(AssistantMessage.builder()
                .content("检索")
                .toolCalls(List.of(ToolCallHelper.of("g2", "Grep", Map.of("pattern", "Bar"))))
                .build());
        history.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("g2", "Grep", "src/Bar.java:10:class Bar")))
                .build());

        List<Message> out = snip.apply(history);
        assertTrue(mentions(out, "g2"), "有命中的结果应保留");
    }

    private boolean mentions(List<Message> messages, String id) {
        for (Message m : messages) {
            if (m instanceof AssistantMessage am && am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall c : am.getToolCalls()) {
                    if (id.equals(c.id())) {
                        return true;
                    }
                }
            }
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

    /** 每个 ToolCall 都要有配对的 ToolResponse（成对铁律）。 */
    private void assertNoDanglingToolCalls(List<Message> messages) {
        List<String> responseIds = new ArrayList<>();
        for (Message m : messages) {
            if (m instanceof ToolResponseMessage trm) {
                trm.getResponses().forEach(r -> responseIds.add(r.id()));
            }
        }
        for (Message m : messages) {
            if (m instanceof AssistantMessage am && am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall c : am.getToolCalls()) {
                    assertTrue(responseIds.contains(c.id()), "不应留下悬空工具调用 " + c.id());
                }
            }
        }
        assertEquals(responseIds.size(), responseIds.stream().distinct().count(), "结果 id 不应重复");
    }
}
