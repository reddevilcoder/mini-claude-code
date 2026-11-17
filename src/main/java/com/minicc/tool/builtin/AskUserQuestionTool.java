package com.minicc.tool.builtin;

import com.minicc.ask.Answer;
import com.minicc.ask.Question;
import com.minicc.ask.QuestionAsker;
import com.minicc.tool.Schemas;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AskUserQuestion 工具（M3）：模型主动发起的提问工具。
 *
 * <p>需求不明确、或存在多个都合理的做法时，由模型调用它弹出选择卡片，把问题与候选项交给用户拍板，
 * 而不是凭空假设。它和审批卡片的根本区别：审批卡片由 <b>harness 强制发起</b>（准不准这么做），
 * AskUserQuestion 由 <b>模型主动调用</b>（要不要/怎么做）。实现上它就是注册进 ToolRegistry 的一个普通工具，
 * 走和其它工具完全一样的调用路径——只是执行时会通过 {@link QuestionAsker} 弹卡片、等用户作答。
 *
 * <p>只读（不碰文件、不碰环境，因此<b>无需审批</b>），但不可并行（要暂停循环等用户交互）。
 *
 * <p>已简化：纯文字选项 + 可选多选 + 自动补"其他"，
 * 不做 preview 并排预览、每题 notes、多问题并排提交等富交互。
 *
 * <p><b>面试关注：Human-in-the-loop 怎么融入 Agent？</b>把等待用户答复也建模为工具执行，
 * 回复作为普通工具观察进入下一次模型请求，主循环无需增加“正在澄清”的专属分支。
 * QuestionAsker 抽离终端交互，测试可直接注入固定回答。该工具只收集信息，不自动授予写文件或执行命令的权限。
 * 描述中的选项数量、文案长度等建议没有全部变成硬校验，实际保证应以 parseQuestions 的检查为准。
 */
public class AskUserQuestionTool implements Tool {

    public static final String NAME = "AskUserQuestion";

    private final QuestionAsker asker; // 弹问题卡片、等用户选（CLI 实现，底层共用 CardPrompter）

    public AskUserQuestionTool(QuestionAsker asker) {
        this.asker = asker;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                在需求不明确、或存在多个都合理的实现方案时，主动向用户提问以收集偏好、澄清歧义、让其在方案间做选择，
                而不是自行假设。用法：
                - questions 为 1–4 个问题；每个问题含 question（完整问题，以问号结尾）、header（≤12 字的短标签）、
                  options（2–4 个候选项，每项含 label 简短选项与 description 取舍说明）。
                - 不要自带"其他"选项，系统会自动补充一个让用户自由输入。
                - 若你推荐某个选项，把它放在第一个，并在其 label 末尾标注 "(Recommended)"。
                - multiSelect 为 true 时允许用户多选。
                - 仅在确有必要时提问；能自行合理决定的小事不要打断用户。""";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        // option: {label, description}
        Map<String, Object> optionProps = new LinkedHashMap<>();
        optionProps.put("label", Schemas.prop("string", "1–5 词的简短选项"));
        optionProps.put("description", Schemas.prop("string", "这个选项意味着什么、有什么取舍"));
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("type", "object");
        option.put("properties", optionProps);
        option.put("required", List.of("label", "description"));

        // question: {question, header, options[], multiSelect}
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("type", "array");
        options.put("description", "2–4 个候选项（不要自带'其他'）");
        options.put("items", option);

        Map<String, Object> questionProps = new LinkedHashMap<>();
        questionProps.put("question", Schemas.prop("string", "完整问题文本，以问号结尾"));
        questionProps.put("header", Schemas.prop("string", "很短的标签（≤12 字）"));
        questionProps.put("options", options);
        questionProps.put("multiSelect", Schemas.prop("boolean", "是否允许多选，默认 false"));
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "object");
        question.put("properties", questionProps);
        question.put("required", List.of("question", "header", "options"));

        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("type", "array");
        questions.put("description", "要问的 1–4 个问题");
        questions.put("items", question);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("questions", questions);
        return Schemas.object(props, List.of("questions"));
    }

    @Override
    public boolean isReadOnly() {
        return true; // 不碰文件/环境，无需审批
    }

    @Override
    public boolean isConcurrencySafe() {
        return false; // 要暂停循环、与用户交互，不能并行
    }

    @Override
    public ToolResult execute(AssistantMessage.ToolCall call, ToolContext ctx) {
        List<Question> questions;
        try {
            questions = parseQuestions(call);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("AskUserQuestion 参数不合法：" + e.getMessage());
        }
        List<Answer> answers = asker.askQuestions(questions); // 弹卡片、等用户选（自动加"其他"）
        if (answers.isEmpty()) {
            return ToolResult.ok("用户未作答（可能已取消）。请你先自行判断，或稍后再确认。");
        }
        return ToolResult.ok(formatAnswers(answers));
    }

    /** 把参数里的 questions 解析成 1–4 个 {@link Question}；不合法时抛 IllegalArgumentException。 */
    @SuppressWarnings("unchecked")
    private static List<Question> parseQuestions(AssistantMessage.ToolCall call) {
        Object raw = ToolCallHelper.arguments(call).get("questions");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("questions 必须是非空数组（1–4 个问题）。");
        }
        if (list.size() > 4) {
            throw new IllegalArgumentException("questions 最多 4 个，收到 " + list.size() + " 个。");
        }
        List<Question> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("每个问题必须是对象。");
            }
            String question = str(m.get("question"));
            String header = str(m.get("header"));
            if (question.isBlank() || header.isBlank()) {
                throw new IllegalArgumentException("每个问题都要有 question 与 header。");
            }
            Object rawOptions = m.get("options");
            if (!(rawOptions instanceof List<?> opts) || opts.size() < 2) {
                throw new IllegalArgumentException("每个问题需要至少 2 个 options。");
            }
            List<Question.Option> options = new ArrayList<>();
            for (Object po : opts) {
                if (!(po instanceof Map<?, ?> pm)) {
                    throw new IllegalArgumentException("每个 option 必须是对象 {label, description}。");
                }
                String label = str(pm.get("label"));
                if (label.isBlank()) {
                    throw new IllegalArgumentException("每个 option 都要有 label。");
                }
                options.add(new Question.Option(label, str(pm.get("description"))));
            }
            boolean multiSelect = Boolean.parseBoolean(str(m.get("multiSelect")));
            result.add(new Question(header, question, options, multiSelect));
        }
        return result;
    }

    /** 把用户的回答整理成一段文字，作为观察回填给模型。 */
    private static String formatAnswers(List<Answer> answers) {
        StringBuilder sb = new StringBuilder("用户已作答：");
        for (Answer a : answers) {
            sb.append("\n- [").append(a.header()).append("] ").append(a.question())
              .append("\n  选择：").append(String.join("、", a.choices()));
        }
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
