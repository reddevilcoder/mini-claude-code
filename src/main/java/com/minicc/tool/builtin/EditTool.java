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
 *
 * <p><b>面试关注：为什么用精确字符串替换？</b>模型只需提交局部原文和新文，参数通常比整文件重写短；
 * old_string 必须唯一，让工具拒绝有歧义的定位。采用字面量而非正则，避免代码中的括号、反斜杠、美元符号
 * 意外被当成表达式或替换引用。replace_all 统计和替换均按不重叠匹配处理，不代表语法级重命名。
 *
 * <p><b>面试追问：改前先读能防并发覆盖吗？</b>当前只检查曾读标记，没有 mtime/版本比对。
 * 从 hasRead、readString 到 writeString 之间存在检查与使用的竞态（TOCTOU）；唯一匹配只能降低误改概率，
 * 不能阻止外部进程恰好在写入前改文件。写入也没有临时文件原子替换或回滚机制。工具串行只约束本调度器，
 * 不会锁住编辑器或其他 Agent；要增强保证需补充版本校验、写入协调及适合目标文件系统的提交方式。
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

    /** 一次编辑的计算结果：要么是错误，要么是“原内容 → 新内容”。 */
    private record Planned(ToolResult error, Path path, String filePath, String before, String updated,
                           int occurrences, boolean replaceAll, String oldString, String newString) {
        static Planned fail(String message) {
            return new Planned(ToolResult.error(message), null, null, null, null, 0, false, null, null);
        }
    }

    /**
     * 校验参数并算出新内容（M10：预览与执行共用，不写盘）。
     */
    private Planned plan(AssistantMessage.ToolCall call, ToolContext ctx) {
        String filePath = ToolCallHelper.getString(call, "file_path", "").trim();
        if (filePath.isEmpty()) {
            return Planned.fail("缺少必填参数 file_path。");
        }
        String oldString = ToolCallHelper.getString(call, "old_string", "");
        String newString = ToolCallHelper.getString(call, "new_string", "");
        boolean replaceAll = ToolCallHelper.getBool(call, "replace_all", false);

        Path path = Path.of(filePath);
        if (!path.isAbsolute()) {
            return Planned.fail("file_path 必须是绝对路径，收到的是相对路径：" + filePath);
        }
        if (!Files.exists(path)) {
            return Planned.fail("文件不存在：" + filePath + "。新建文件请用 Write 工具。");
        }
        if (Files.isDirectory(path)) {
            return Planned.fail("这是一个目录，不是文件：" + filePath);
        }
        // 改前先读校验：逼模型基于真实内容构造修改。
        if (!ctx.workspace().hasRead(path)) {
            return Planned.fail("修改前请先用 Read 读取该文件：" + filePath);
        }
        if (oldString.isEmpty()) {
            return Planned.fail("old_string 不能为空；新建文件请用 Write。");
        }
        if (oldString.equals(newString)) {
            return Planned.fail("old_string 与 new_string 相同，无需修改。");
        }
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Planned.fail("修改文件失败：" + e.getMessage());
        }
        int occurrences = countOccurrences(content, oldString);
        if (occurrences == 0) {
            return Planned.fail("未在文件中找到 old_string。请对照 Read 的输出，确认内容与缩进完全一致。");
        }
        if (occurrences > 1 && !replaceAll) {
            return Planned.fail("old_string 在文件中出现了 " + occurrences
                    + " 次，不唯一。请补充更多上下文使其唯一，或将 replace_all 设为 true 以全部替换。");
        }
        // 把 old_string 当字面量替换（不是正则）。replace_all 决定替换一次还是全部。
        String updated = replaceAll
                ? replaceLiteralAll(content, oldString, newString)
                : replaceLiteralFirst(content, oldString, newString);
        return new Planned(null, path, filePath, content, updated, occurrences, replaceAll, oldString, newString);
    }

    @Override
    public FilePreview preview(AssistantMessage.ToolCall call, ToolContext ctx) {
        Planned p = plan(call, ctx);
        return p.error() != null ? null : new FilePreview(p.path(), p.before(), p.updated());
    }

    @Override
    public ToolResult execute(AssistantMessage.ToolCall call, ToolContext ctx) {
        Planned p = plan(call, ctx);
        if (p.error() != null) {
            return p.error();
        }
        try {
            // M10：紧挨着写入之前备份；备份失败则不写（没有备份的改动无法回退）。
            ctx.checkpoint().before(p.path());
            // 整文件写回，不是文件系统层面的局部 patch；此处失败或进程崩溃时不能承诺原内容完整保留。
            Files.writeString(p.path(), p.updated(), StandardCharsets.UTF_8);
            ctx.checkpoint().after(p.path());

            int n = p.replaceAll() ? p.occurrences() : 1;
            int removed = lineCount(p.oldString()) * n;
            int added = lineCount(p.newString()) * n;
            String summary = "已修改 " + p.filePath() + "（替换 " + n + " 处，+" + added + " -" + removed + " 行）";
            return ToolResult.ok(summary + "\n" + snippet(p.updated(), p.newString()));
        } catch (IOException e) {
            return ToolResult.error("修改文件失败：" + e.getMessage());
        }
    }

    /** 统计子串出现次数（字面量，不重叠）。调用方已拒绝空串，保证每次 idx 都能前进，避免死循环。 */
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
