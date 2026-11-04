package com.minicc.compact;

import com.minicc.tool.ToolCallHelper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TokenEstimator 的单元测试：粗略字符→Token 估算，量级正确即可。 */
class TokenEstimatorTest {

    @Test
    void emptyAndNullAreZero() {
        assertEquals(0, TokenEstimator.ofText(null));
        assertEquals(0, TokenEstimator.ofText(""));
        assertEquals(0, TokenEstimator.ofMessages(null));
    }

    @Test
    void scalesWithCharacterCount() {
        // 3.5 字符/Token，向上取整：7 字符 → 2 Token。
        assertEquals(2, TokenEstimator.ofText("1234567"));
        // 更长的文本应得到更大的估值。
        assertTrue(TokenEstimator.ofText("a".repeat(350)) > TokenEstimator.ofText("a".repeat(35)));
    }

    @Test
    void countsToolCallArgsAndResults() {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("要读文件")
                .toolCalls(List.of(ToolCallHelper.of("c1", "Read", Map.of("file_path", "/tmp/a.txt"))))
                .build();
        ToolResponseMessage result = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "Read", "x".repeat(70))))
                .build();
        List<Message> messages = List.of(new UserMessage("读一下"), assistant, result);

        // 累加三条消息的文本/参数/结果字符后应为正数，且结果那条占大头。
        assertTrue(TokenEstimator.ofMessages(messages) >= TokenEstimator.ofMessage(result));
        assertTrue(TokenEstimator.ofMessage(result) >= 20);
    }
}
