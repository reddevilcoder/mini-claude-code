package com.minicc.context;

import com.minicc.session.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PlanModeStage 的单元测试：计划态开启时注入「只调研不修改」提示，非计划态时零注入。 */
class PlanModeStageTest {

    private final PlanModeStage stage = new PlanModeStage();

    @Test
    void injectsPromptWhenInPlanMode(@TempDir Path project) {
        Session session = new Session(project);
        session.planMode().enterPlanMode();

        List<Message> messages = new ArrayList<>();
        stage.contribute(messages, session);

        assertEquals(1, messages.size(), "计划态下应注入恰好一条系统消息");
        assertEquals(MessageType.SYSTEM, messages.get(0).getMessageType());
        String text = messages.get(0).getText();
        assertTrue(text.contains("Plan Mode"), "提示应说明处于 Plan Mode");
        assertTrue(text.contains("ExitPlanMode"), "提示应引导用 ExitPlanMode 请求批准");
    }

    @Test
    void injectsNothingWhenNotInPlanMode(@TempDir Path project) {
        Session session = new Session(project);   // 默认非计划态

        List<Message> messages = new ArrayList<>();
        stage.contribute(messages, session);

        assertTrue(messages.isEmpty(), "非计划态时不应往上下文加任何东西");
    }
}
