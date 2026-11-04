package com.minicc.tool.builtin;

import com.minicc.tool.Schemas;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Edit 工具：对已有文件做精确的字符串替换式修改。
 *
 * <p>对外语义：
 * <ul>
 *   <li>改前必须先 Read（否则报错）；</li>
 *   <li>old_string 必须在文件中出现；找不到则失败；</li>
 *   <li>replace_all=false 时 old_string 必须唯一，否则提示加上下文或用 replace_all；</li>
 *   <li>replace_all=true 时替换所有匹配（适合变量重命名这类全量替换）。</li>
 * </ul>
 *
 * <p>M2 简化：一次只做一处替换（不支持多条编辑批处理），只做精确匹配
 * （不做卷曲引号归一化、de-sanitize 等容错）。
 */
public class EditTool implements Tool {

    @Override
    public String name() {
        return "Edit";
    }

    @Override
    public String description() {
        return """
                对文件执行精确的字符串替换。
                用法：
                - 编辑前必须先用 Read 读取该文件，否则本工具会报错。
                - 保留 Read 输出里"行号前缀之后"的真实缩进，绝不要把行号前缀写进 old_string 或 new_string。
                - 优先编辑已有文件，不要动辄新建文件。
                - 若 old_string 在文件中不唯一，编辑会失败：要么提供更多上下文使其唯一，要么用 replace_all。
                - replace_all 用于把某个字符串在文件中全部替换（例如重命名变量）。""";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("file_path", Schemas.prop("string", "要修改文件的绝对路径"));
        props.put("old_string", Schemas.prop("string", "要被替换掉的原文（需与文件内容精确匹配，含缩进）"));
        props.put("new_string", Schemas.prop("string", "替换后的新内容（必须不同于 old_string）"));
        props.put("replace_all", Schemas.prop("boolean", "是否替换所有匹配，默认 false"));
        return Schemas.object(props, List.of("file_path", "old_string", "new_string"));
    }

    @Override
    public boolean isReadOnly() {
        return false; // 写操作：M3 的审批会挂在它执行之前的钩子上
    }

    @Override
    public boolean isConcurrencySafe() {
        return false; // 改文件，必须串行执行
    }

    @Override
    public ToolResult execute(AssistantMessage.ToolCall call, ToolContext ctx) {
        String filePath = ToolCallHelper.getString(call, "file_path", "").trim();
        if (filePath.isEmpty()) {
            return ToolResult.error("缺少必填参数 file_path。");
        }
        String oldString = ToolCallHelper.getString(call, "old_string", "");
        String newString = ToolCallHelper.getString(call, "new_string", "");
        boolean replaceAll = ToolCallHelper.getBool(call, "replace_all", false);

        Path path = Path.of(filePath);
        if (!path.isAbsolute()) {
            return ToolResult.error("file_path 必须是绝对路径，收到的是相对路径：" + filePath);
        }
        if (!Files.exists(path)) {
            return ToolResult.error("文件不存在：" + filePath + "。新建文件请用 Write 工具。");
        }
        if (Files.isDirectory(path)) {
            return ToolResult.error("这是一个目录，不是文件：" + filePath);
        }
        // 改前先读校验：逼模型基于真实内容构造修改。
        if (!ctx.workspace().hasRead(path)) {
            return ToolResult.error("修改前请先用 Read 读取该文件：" + filePath);
        }
        if (oldString.isEmpty()) {
            return ToolResult.error("old_string 不能为空；新建文件请用 Write。");
        }
        if (oldString.equals(newString)) {
            return ToolResult.error("old_string 与 new_string 相同，无需修改。");
        }

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);

            int occurrences = countOccurrences(content, oldString);
            if (occurrences == 0) {
                return ToolResult.error("未在文件中找到 old_string。请对照 Read 的输出，确认内容与缩进完全一致。");
            }
            if (occurrences > 1 && !replaceAll) {
                return ToolResult.error("old_string 在文件中出现了 " + occurrences
                        + " 次，不唯一。请补充更多上下文使其唯一，或将 replace_all 设为 true 以全部替换。");
            }

            // 把 old_string 当字面量替换（不是正则）。replace_all 决定替换一次还是全部。
            String updated = replaceAll
                    ? replaceLiteralAll(content, oldString, newString)
                    : replaceLiteralFirst(content, oldString, newString);

            Files.writeString(path, updated, StandardCharsets.UTF_8);

            int removed = lineCount(oldString) * (replaceAll ? occurrences : 1);
            int added = lineCount(newString) * (replaceAll ? occurrences : 1);
            String summary = "已修改 " + filePath + "（替换 "
                    + (replaceAll ? occurrences : 1) + " 处，+" + added + " -" + removed + " 行）";
            return ToolResult.ok(summary + "\n" + snippet(updated, newString));
        } catch (IOException e) {
            return ToolResult.error("修改文件失败：" + e.getMessage());
        }
    }

    /** 统计子串出现次数（字面量，不重叠）。 */
    private static int countOccurrences(String content, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /** 字面量替换第一处。 */
    private static String replaceLiteralFirst(String content, String oldStr, String newStr) {
        int idx = content.indexOf(oldStr);
        return content.substring(0, idx) + newStr + content.substring(idx + oldStr.length());
    }

    /** 字面量替换全部（String.replace 本身就是字面量替换，这里直接用）。 */
    private static String replaceLiteralAll(String content, String oldStr, String newStr) {
        return content.replace(oldStr, newStr);
    }

    /** 粗略的行数（用于 +/- 摘要）。 */
    private static int lineCount(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        return (int) s.chars().filter(c -> c == '\n').count() + 1;
    }

    /** 取修改后新内容附近几行，带 cat -n 行号，便于模型确认改对了位置。 */
    private static String snippet(String updated, String newString) {
        String[] lines = updated.split("\n", -1);
        // 定位 new_string 首行在新文件中的位置。
        int anchor = 0;
        String firstNewLine = newString.split("\n", -1)[0];
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(firstNewLine) && !firstNewLine.isBlank()) {
                anchor = i;
                break;
            }
        }
        int from = Math.max(0, anchor - 3);
        int to = Math.min(lines.length, anchor + Math.max(1, lineCount(newString)) + 3);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(String.format("%6d\t%s%n", i + 1, lines[i]));
        }
        return sb.toString().stripTrailing();
    }
}
