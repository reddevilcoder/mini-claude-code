package com.minicc.memory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 记忆目录门面：负责 auto memory 的「目录管理」与「读」——保证目录存在、读入 {@code MEMORY.md}
 * 索引（带上限截断）。主题文件由模型看着索引按需 Read，不在这里再扫一遍目录。
 *
 * <p>注意：记忆的「写」<b>不在这里</b>——那走模型调用的 Read/Write 工具（Write 不只用于写业务代码，
 * 也用于写入长期记忆）。本类只做「读」与「目录管理」，不替模型写文件。
 *
 * <p>一个关键概念：{@code MEMORY.md} 是<b>索引</b>而非正文——它始终整份加载进上下文（所以短），
 * 真正的内容拆在 {@code feedback_xx.md} / {@code project_xx.md} 等主题文件里，模型看着索引按需 Read，
 * 这就是「渐进式披露」。
 *
 * <p><b>面试关注：这是怎样的长期记忆？</b>它是“文件索引 + 模型按需读主题文件”，
 * 没有 embedding、向量库、相似度检索或自动召回排序。信息能否被利用，取决于索引质量和模型选择，
 * 不能把文件存在等同于每轮必然成功召回。相比整份历史注入，它节省上下文，但会增加按需读文件的轮次。
 *
 * <p>索引控制的是“带入提示词的成本”，不是磁盘文件写入大小。行数和 UTF-8 字节上限也不是
 * 精确 Token 上限；追加的告警文本另计。目录允许写入不代表记忆内容永远正确，
 * 过时、重复或冲突的信息仍需在保存策略与提示词层面治理。
 */
public class AutoMemory {

    /** 入口索引文件名。 */
    public static final String INDEX_NAME = MemoryPromptText.ENTRYPOINT_NAME;

    /** 索引行数上限。 */
    static final int MAX_INDEX_LINES = 200;
    /** 索引字节数上限。 */
    static final int MAX_INDEX_BYTES = 25_000;

    private final Path memoryDir;

    public AutoMemory(Path memoryDir) {
        this.memoryDir = memoryDir;
    }

    /** 本项目的记忆目录（供 {@link MemoryPromptText#behavioralGuide} 与审批放行判断使用）。 */
    public Path dir() {
        return memoryDir;
    }

    /** 启动时创建记忆目录。已存在则直接返回；失败只打印，留给后续 Write 报错。 */
    public void ensureMemoryDirExists() {
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            System.err.println("创建记忆目录失败（已忽略，模型写入时会再尝试）：" + e.getMessage());
        }
    }

    /**
     * 逐行读入 {@code MEMORY.md}，行数或 UTF-8 字节数任一超限就停，避免通常情况下整份加载。
     * 停在完整行上；超限时在末尾附一句告警。文件不存在或去掉空白后为空时返回 {@link Optional#empty()}。
     *
     * <p>边界：readLine 会先构造完整一行，再检查字节预算，因此超长单行仍可能临时占用大量内存；
     * 这是注入结果预算，不是严格的读取内存上限。跳过的开头空白行也不计入行数预算。
     */
    public Optional<String> readMemoryIndex() {
        Path index = memoryDir.resolve(INDEX_NAME);
        if (!Files.isRegularFile(index)) {
            return Optional.empty();
        }
        try (BufferedReader reader = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
            StringBuilder body = new StringBuilder();
            int lines = 0;
            int bytes = 0;
            String exceeded = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (body.isEmpty() && line.isBlank()) {
                    continue;
                }
                int extra = line.getBytes(StandardCharsets.UTF_8).length + (body.isEmpty() ? 0 : 1);
                if (lines >= MAX_INDEX_LINES || bytes + extra > MAX_INDEX_BYTES) {
                    exceeded = limitReason(lines >= MAX_INDEX_LINES, bytes + extra > MAX_INDEX_BYTES);
                    break;
                }
                if (!body.isEmpty()) {
                    body.append('\n');
                }
                body.append(line);
                lines++;
                bytes += extra;
            }
            String text = body.toString().strip();
            if (text.isEmpty() && exceeded == null) {
                return Optional.empty();
            }
            if (exceeded == null) {
                return Optional.of(text);
            }
            String warning = "> WARNING: " + INDEX_NAME + " 已超过" + exceeded
                    + "，只加载了其中一部分。请把索引保持成一行一条（~150 字符内），把正文移进主题文件。";
            return Optional.of(text.isEmpty() ? warning : text + "\n\n" + warning);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** 超限告警里说明撞上的是行数、字节，还是两者一起。 */
    private static String limitReason(boolean overLines, boolean overBytes) {
        if (overLines && overBytes) {
            return "行数（上限 " + MAX_INDEX_LINES + "）和字节（上限 " + MAX_INDEX_BYTES + "）";
        }
        if (overLines) {
            return "行数（上限 " + MAX_INDEX_LINES + "）";
        }
        return "字节（上限 " + MAX_INDEX_BYTES + "）——索引行写得太长";
    }
}
