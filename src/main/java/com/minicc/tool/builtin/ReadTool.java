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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read 工具：读取本地文件内容，可指定行范围。
 *
 * <p>对外行为：绝对路径、offset/limit、cat -n 行号格式、默认最多 2000 行、
 * 空文件告警、二进制拒绝、文件不存在时给出友好提示。
 *
 * <p>M1 做了简化：只读文本文件（不处理图片/PDF/Notebook），不做去重缓存、Skills 发现、分析埋点。
 * 但保留一个关键约定——Read 结果永不落盘（结果大小阈值视作无限），
 * 否则模型拿到路径又会再 Read 形成循环；M6 的工具结果压缩会读取这个约定。
 *
 * <p><b>面试关注：offset/limit 是流式分段读取吗？</b>当前先把整个文件读成字节、解码并拆行，
 * 再截取展示区间，所以它限制的是返回内容，内存开销仍与整个文件大小有关；超过 10 MiB 的文件在截取前
 * 就被拒绝，给 offset/limit 也不能绕过该上限。生产场景可改为流式逐行跳过和读取，同时限制超长单行。
 *
 * <p><b>面试追问：Read 给写工具提供什么保证？</b>成功读取后在 WorkspaceState 记录“曾读过”，
 * 只用于减少模型凭空编辑，不保存版本或内容哈希。登记发生在行范围裁剪之前，即使只展示一部分内容也算读过。
 * 路径要求绝对路径是为消除定位歧义，不意味着只允许访问项目目录；文件读取范围也没有被 Bash 的进程沙箱覆盖。
 * 扩展名和前 8000 字节的 NUL 探测属于启发式二进制判断，并不保证全部编码都能正确识别。
 */
public class ReadTool implements Tool {

    /** 默认最多读取的行数。 */
    private static final int DEFAULT_LIMIT = 2000;

    /** 文件大小上限（字节），超过则拒绝，避免一次性读入超大文件。 */
    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    /** 常见二进制文件扩展名，按扩展名快速拒绝。 */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "class", "jar", "war", "zip", "tar", "gz", "7z", "rar",
            "exe", "dll", "so", "dylib", "o", "a", "bin",
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "pdf",
            "mp3", "mp4", "mov", "avi", "woff", "woff2", "ttf", "otf", "eot"
    );

    @Override
    public String name() {
        return "Read";
    }

    @Override
    public String description() {
        return """
                读取本地文件系统中的一个文件。
                用法：
                - file_path 必须是绝对路径，不能是相对路径。
                - 默认从文件开头读取，最多 2000 行；文件很大时用 offset 和 limit 读取指定部分。
                - 返回内容采用 cat -n 格式，带从 1 开始的行号。
                - 只能读取文件，不能读取目录。
                - 读取不存在的文件会返回错误（可据此改用 Glob 查找正确路径）。""";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("file_path", Schemas.prop("string", "要读取文件的绝对路径"));
        props.put("offset", Schemas.prop("integer", "起始行号（从 1 开始，可选；仅在文件很大需要分段读时提供）"));
        props.put("limit", Schemas.prop("integer", "读取的行数（可选；仅在需要分段读时提供）"));
        return Schemas.object(props, List.of("file_path"));
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public boolean isConcurrencySafe() {
        return true;
    }

    /**
     * Read 的结果永不落盘（结果大小阈值视作无限）。
     *
     * <p>用 {@code -1} 表示无限大。否则一旦把 Read 的结果落盘、历史里只留文件路径，
     * 模型拿到路径又会再 Read，形成"读路径→落盘→再读路径"的死循环。
     */
    @Override
    public int maxResultChars() {
        return -1;
    }

    @Override
    public ToolResult execute(AssistantMessage.ToolCall call, ToolContext ctx) {
        String filePath = ToolCallHelper.getString(call, "file_path", "").trim();
        if (filePath.isEmpty()) {
            return ToolResult.error("缺少必填参数 file_path。");
        }

        Path path = Path.of(filePath);
        // 要求绝对路径（避免相对路径带来的歧义）。
        if (!path.isAbsolute()) {
            return ToolResult.error("file_path 必须是绝对路径，收到的是相对路径：" + filePath);
        }

        if (!Files.exists(path)) {
            return ToolResult.error("文件不存在：" + filePath + "。可用 Glob 按文件名模式查找正确路径。");
        }
        if (Files.isDirectory(path)) {
            return ToolResult.error("这是一个目录，不是文件：" + filePath + "。可用 Glob 或 Grep 查看目录内容。");
        }

        // 按扩展名拒绝二进制文件。
        String ext = extensionOf(path);
        if (BINARY_EXTENSIONS.contains(ext)) {
            return ToolResult.error("无法读取二进制文件（." + ext + "）：" + filePath);
        }

        try {
            long size = Files.size(path);
            if (size > MAX_SIZE_BYTES) {
                return ToolResult.error("文件过大（" + size + " 字节），超过上限。请用 offset 和 limit 分段读取，或改用 Grep 检索。");
            }

            // 文件大小检查与读取不是原子操作；外部进程仍可能在两者之间改动文件。
            byte[] bytes = Files.readAllBytes(path);
            // 内容若含 NUL 字节，判定为二进制并拒绝（兜住扩展名没覆盖的情况）。
            if (looksBinary(bytes)) {
                return ToolResult.error("无法读取二进制文件：" + filePath);
            }

            String text = new String(bytes, StandardCharsets.UTF_8);
            List<String> lines = splitLines(text);
            int totalLines = lines.size();

            // 登记"本会话读过此文件"，供 Edit/Write 的"改前先读"校验（M2）。
            ctx.workspace().markRead(path);

            if (totalLines == 0) {
                return ToolResult.ok("<系统提醒：文件存在但内容为空。>");
            }

            int offset = Math.max(1, ToolCallHelper.getInt(call, "offset", 1));
            int limit = ToolCallHelper.getInt(call, "limit", DEFAULT_LIMIT);
            if (limit <= 0) {
                limit = DEFAULT_LIMIT;
            }

            if (offset > totalLines) {
                return ToolResult.ok("<系统提醒：文件只有 " + totalLines + " 行，起始行 " + offset + " 超出范围。>");
            }

            int from = offset - 1;                       // 转成 0 基下标
            int to = Math.min(totalLines, from + limit); // 右开区间

            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                // cat -n 风格：行号右对齐 6 位，制表符分隔。
                sb.append(String.format("%6d\t%s%n", i + 1, lines.get(i)));
            }
            // 若还有更多行未读，给模型一个提示，便于它决定是否继续分段读。
            if (to < totalLines) {
                sb.append(String.format("%n<系统提醒：文件共 %d 行，本次读取第 %d-%d 行；如需更多，用 offset=%d 继续读取。>%n",
                        totalLines, offset, to, to + 1));
            }
            return ToolResult.ok(sb.toString());
        } catch (IOException e) {
            return ToolResult.error("读取文件失败：" + e.getMessage());
        }
    }

    /** 取小写扩展名（不含点），没有扩展名则返回空串。 */
    private static String extensionOf(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    /** 粗略判断是否为二进制：前若干字节里出现 NUL。 */
    private static boolean looksBinary(byte[] bytes) {
        int scan = Math.min(bytes.length, 8000);
        for (int i = 0; i < scan; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** 按行拆分，兼容 \n 与 \r\n，保留中间空行。 */
    private static List<String> splitLines(String text) {
        // -1 保留末尾空串之外的结构；这里用 split 后手动处理末尾换行。
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty()) {
            return List.of();
        }
        String[] arr = normalized.split("\n", -1);
        // 若文件以换行结尾，split 会多出一个末尾空串，去掉它以免多显示一行。
        List<String> list = new ArrayList<>(List.of(arr));
        if (!list.isEmpty() && list.get(list.size() - 1).isEmpty() && normalized.endsWith("\n")) {
            list.remove(list.size() - 1);
        }
        return list;
    }
}
