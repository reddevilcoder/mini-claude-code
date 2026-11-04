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
 * Write 工具：新建文件或整体覆盖写入。
 *
 * <p>对外行为：
 * <ul>
 *   <li>会覆盖同路径的已有文件；</li>
 *   <li>改已有文件前必须先 Read（新建文件则不需要）；</li>
 *   <li>优先用 Edit 改已有文件，Write 只用于新建或整体重写；</li>
 *   <li>不主动创建 *.md / README，除非用户明确要求。</li>
 * </ul>
 *
 * <p>M2 简化：父目录不存在时自动创建。
 */
public class WriteTool implements Tool {

    @Override
    public String name() {
        return "Write";
    }

    @Override
    public String description() {
        return """
                将内容写入本地文件系统的一个文件。
                用法：
                - 若目标路径已存在文件，本工具会覆盖它。
                - 若是修改已有文件，必须先用 Read 读取它，否则本工具会报错。
                - 修改已有文件时优先用 Edit（只发送 diff，更安全）；Write 仅用于新建文件或整体重写。
                - 不要主动创建文档文件（*.md）或 README，除非用户明确要求。""";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("file_path", Schemas.prop("string", "目标文件的绝对路径"));
        props.put("content", Schemas.prop("string", "要写入的完整内容"));
        return Schemas.object(props, List.of("file_path", "content"));
    }

    @Override
    public boolean isReadOnly() {
        return false; // 写操作
    }

    @Override
    public boolean isConcurrencySafe() {
        return false; // 改文件，必须串行
    }

    @Override
    public ToolResult execute(AssistantMessage.ToolCall call, ToolContext ctx) {
        String filePath = ToolCallHelper.getString(call, "file_path", "").trim();
        if (filePath.isEmpty()) {
            return ToolResult.error("缺少必填参数 file_path。");
        }
        String content = ToolCallHelper.getString(call, "content", "");

        Path path = Path.of(filePath);
        if (!path.isAbsolute()) {
            return ToolResult.error("file_path 必须是绝对路径，收到的是相对路径：" + filePath);
        }
        if (Files.isDirectory(path)) {
            return ToolResult.error("这是一个目录，不是文件：" + filePath);
        }

        boolean existed = Files.exists(path);
        // 覆盖已有文件前必须先读过它；新建文件则不需要。
        if (existed && !ctx.workspace().hasRead(path)) {
            return ToolResult.error("覆盖已有文件前请先用 Read 读取它：" + filePath);
        }

        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent()); // 父目录不存在则自动创建
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
            // 写入后视为"已读"，便于随后继续用 Edit 修改它。
            ctx.workspace().markRead(path);

            int lines = content.isEmpty() ? 0 : (int) content.chars().filter(c -> c == '\n').count() + 1;
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            String verb = existed ? "已覆盖" : "已新建";
            return ToolResult.ok(verb + " " + filePath + "（" + lines + " 行，" + bytes + " 字节）");
        } catch (IOException e) {
            return ToolResult.error("写入文件失败：" + e.getMessage());
        }
    }
}
