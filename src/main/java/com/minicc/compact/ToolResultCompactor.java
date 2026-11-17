package com.minicc.compact;

import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 第①层：工具结果预算（落盘到 {@code tool-results/}）。
 *
 * <p>预览上限名为 {@code PREVIEW_BYTES = 2000}，当前实际按 Java 字符串的 UTF-16 代码单元截取，
 * 不是严格的 UTF-8 字节预算；含中文时文件字节数会明显不同。
 *
 * <p><b>针对对象：刚产生的那一条工具结果</b>（入库前，不读历史、不读 JSONL、不基于投影链的上一步）。
 * 这是五层里<b>唯一会改写持久状态</b>的一层：单条结果超过该工具的字符阈值时，把<b>完整原文</b>落盘到
 * {@code tool-results/<callId>.txt}，而写进内存历史与 JSONL 的那条结果换成一段"引用消息"
 * （文件绝对路径 + 前 ~2KB 预览 + 总字符数）。完整原文没丢，只是"家"从 JSONL 内联搬到了旁边的文件，
 * 模型随时能用 Read 取回。
 *
 * <p>因为是"产生时落一次"，决策天然冻结、不重复：之后每轮投影、{@code /resume} 读回看到的都是同一份
 * 预览+路径，{@code tool-results/} 里也一直是那份完整原文——既不用进每轮投影链，也不用缓存"哪些已落盘"。
 *
 * <p><b>面试关注：为什么先写原文，再把消息换成路径？</b>只有文件写入成功才返回引用；
 * IO 失败时继续保留原文，让“节省上下文”这个优化不改变工具结果的可用性。
 * 这类似把大对象外置、在主记录中保存引用，也意味着恢复会话需要同时保留 JSONL 和 tool-results 目录。
 * 这里保存的是工具交来的完整结果，若工具此前已经截断输出，本层无法恢复被截掉的部分。
 *
 * <p>{@code CREATE_NEW} 避免覆盖已有文件，但幂等性依赖 callId 在目录内唯一且内容稳定。
 * 文件名清洗可能把不同 id 映射到同名，跨会话重复 id 也可能复用旧文件；本层没有内容哈希校验或事务。
 * 默认给 Read 无限预算，是为了避免读取落盘文件后又得到一个落盘引用的循环，代价是单次大 Read
 * 仍可能挤占上下文，需要调用方合理分页。
 */
public class ToolResultCompactor {

    /** 历史命名保留 BYTES；reference 当前按 substring 截取，实际限制的是 UTF-16 代码单元数量。 */
    static final int PREVIEW_BYTES = 2000;

    private final Path toolResultsDir;                 // M4 的 SessionPaths.toolResultsDir(...)
    private final Map<String, Integer> budgetByTool;   // 工具名 → 字符阈值（-1 表示无限、永不落盘）
    private final int defaultBudget;                   // 未在表中的工具用的默认阈值

    public ToolResultCompactor(Path toolResultsDir, Map<String, Integer> budgetByTool, int defaultBudget) {
        this.toolResultsDir = toolResultsDir;
        this.budgetByTool = budgetByTool;
        this.defaultBudget = defaultBudget;
    }

    /**
     * 入库前调用：逐条看工具结果，单条超阈值的落盘并替换为"预览+路径"，否则原样。
     *
     * <p>返回一条"要入库的" {@link ToolResponseMessage}——它和日志里存的是同一份（大结果已是预览+路径）。
     */
    public ToolResponseMessage apply(ToolResponseMessage trm) {
        List<ToolResponseMessage.ToolResponse> out = new ArrayList<>();
        boolean changed = false;
        for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
            int limit = budgetFor(resp.name());        // -1 = 无限、永不落盘
            String data = resp.responseData();
            if (limit < 0 || data == null || data.length() <= limit) {
                out.add(resp);                         // 不超阈值（或无限）：原样入库
            } else {
                Path file = writeToResultFile(resp.id(), data);   // 写 <callId>.txt（已存在即复用）
                String reference = (file != null)
                        ? reference(file, data)
                        : data;                                     // 落盘失败则退回原文（宁可不压也不丢）
                out.add(new ToolResponseMessage.ToolResponse(resp.id(), resp.name(), reference));
                changed = (file != null) || changed;
            }
        }
        // 没有任何一条被替换时，原样返回入参，避免无谓地重建对象。
        return changed ? ToolResponseMessage.builder().responses(out).build() : trm;
    }

    /** 查某个工具的落盘阈值：表里有就用表里的，否则用默认阈值。 */
    private int budgetFor(String toolName) {
        Integer v = budgetByTool.get(toolName);
        return v != null ? v : defaultBudget;
    }

    /**
     * 落盘一次：把完整原文写到 {@code <toolResultsDir>/<callId>.txt}。
     *
     * <p>同一个 callId 只写一次——用 {@link StandardOpenOption#CREATE_NEW} 保证"文件已存在即复用"
     * （即 {@code 'wx'} / {@code EEXIST} 语义）。落盘失败不抛异常，返回 {@code null}，
     * 由调用方退回原文（压缩是优化，不能因为写盘失败就丢数据或崩溃）。
     */
    private Path writeToResultFile(String callId, String data) {
        try {
            Files.createDirectories(toolResultsDir);
            Path file = toolResultsDir.resolve(sanitize(callId) + ".txt");
            try {
                Files.writeString(file, data, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException eexist) {
                // 已落过盘（同一 callId 内容确定），直接复用这份文件。
            }
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 构造“引用消息”：文件绝对路径 + 前 2000 个 UTF-16 代码单元预览 + 总长度。
     * 输出文案沿用“2KB”，并非按编码后的字节数截断，也没有额外处理代理对边界。
     * 前缀 {@code <工具结果过大} 也是第④层折叠时
     * 识别"这条已落盘、可提取落盘路径"的标志。
     */
    private String reference(Path file, String data) {
        String preview = data.substring(0, Math.min(PREVIEW_BYTES, data.length()));
        return "<工具结果过大，完整内容已落盘：" + file.toAbsolutePath()
                + "（共 " + data.length() + " 字符，可用 Read 读取）。前 2KB 预览：>\n" + preview;
    }

    /** 把 callId 清洗成安全文件名（防止其中有路径分隔符等）。 */
    private static String sanitize(String id) {
        return id == null ? "unknown" : id.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
