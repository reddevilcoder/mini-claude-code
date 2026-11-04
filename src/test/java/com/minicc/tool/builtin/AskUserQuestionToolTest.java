package com.minicc.tool.builtin;

import com.minicc.agent.CancelToken;
import com.minicc.ask.Answer;
import com.minicc.ask.Question;
import com.minicc.ask.QuestionAsker;
import com.minicc.permission.PermissionState;
import com.minicc.session.TodoList;
import com.minicc.session.WorkspaceState;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AskUserQuestionTool 的单元测试：参数解析、回答格式化、非法参数与空回答兜底。 */
class AskUserQuestionToolTest {

    @Test
    void parsesQuestionsAndFormatsAnswers() {
        RecordingAsker asker = new RecordingAsker(List.of(
                new Answer("加密方案", "用哪种加密方案？", List.of("BCrypt"))));
        AskUserQuestionTool tool = new AskUserQuestionTool(asker);

        ToolResult result = tool.execute(ToolCallHelper.of("1", "AskUserQuestion", oneQuestion()), ctx());

        assertFalse(result.error());
        assertTrue(result.content().contains("BCrypt"), "回填的观察应包含用户的选择");
        assertTrue(result.content().contains("加密方案"));
        // 校验工具确实把 1 个问题交给了 QuestionAsker。
        assertTrue(asker.received.size() == 1);
        Question q = asker.received.get(0);
        assertTrue(q.options().size() == 2, "解析出的候选项不含系统自动补的'其他'");
    }

    @Test
    void readOnlyAndNotConcurrencySafe() {
        AskUserQuestionTool tool = new AskUserQuestionTool(new RecordingAsker(List.of()));
        assertTrue(tool.isReadOnly(), "提问不碰文件/环境，应为只读、无需审批");
        assertFalse(tool.isConcurrencySafe(), "要暂停循环等用户作答，不可并行");
    }

    @Test
    void emptyQuestionsReturnsError() {
        AskUserQuestionTool tool = new AskUserQuestionTool(new RecordingAsker(List.of()));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("questions", List.of());
        ToolResult result = tool.execute(ToolCallHelper.of("1", "AskUserQuestion", args), ctx());
        assertTrue(result.error());
    }

    @Test
    void emptyAnswersFallsBackToSelfDecide() {
        // QuestionAsker 返回空回答（用户取消），工具不应报错，而是提示模型自行判断。
        AskUserQuestionTool tool = new AskUserQuestionTool(new RecordingAsker(List.of()));
        ToolResult result = tool.execute(ToolCallHelper.of("1", "AskUserQuestion", oneQuestion()), ctx());
        assertFalse(result.error());
        assertTrue(result.content().contains("自行判断") || result.content().contains("未作答"));
    }

    private static Map<String, Object> oneQuestion() {
        Map<String, Object> opt1 = new LinkedHashMap<>();
        opt1.put("label", "BCrypt");
        opt1.put("description", "Spring Security 自带");
        Map<String, Object> opt2 = new LinkedHashMap<>();
        opt2.put("label", "Argon2");
        opt2.put("description", "更强，但需额外依赖");

        Map<String, Object> q = new LinkedHashMap<>();
        q.put("question", "用哪种加密方案？");
        q.put("header", "加密方案");
        q.put("options", List.of(opt1, opt2));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("questions", List.of(q));
        return args;
    }

    private static ToolContext ctx() {
        return new ToolContext(Path.of("."), new WorkspaceState(), new TodoList(), false, 0,
                new CancelToken(), new PermissionState());
    }

    /** 记录收到的问题、返回预设回答的假 QuestionAsker。 */
    private static class RecordingAsker implements QuestionAsker {
        private final List<Answer> scriptedAnswers;
        final List<Question> received = new ArrayList<>();

        RecordingAsker(List<Answer> scriptedAnswers) {
            this.scriptedAnswers = scriptedAnswers;
        }

        @Override
        public List<Answer> askQuestions(List<Question> questions) {
            received.addAll(questions);
            return scriptedAnswers;
        }
    }
}
