package com.minicc.tool.builtin;

import com.minicc.tool.Schemas;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Glob 工具：按文件名模式查找文件。
 *
 * <p>支持 "**&#47;*.java"、"src/**&#47;*.yml" 这类模式，
 * 结果按文件修改时间倒序排列（最近修改的在前）。
 */
public class GlobTool implements Tool {

    /** 返回的最大文件数，避免结果过大。 */
    private static final int MAX_RESULTS = 200;

    @Override
    public String name() {
        return "Glob";
    }

    @Override
    public String description() {
        return """
                快速的文件名模式匹配工具，适用于任意规模的代码库。
                - 支持 glob 模式，如 "**/*.java" 或 "src/**/*.yml"。
                - 返回匹配的文件路径，按修改时间倒序排列。
                - 当你要按文件名查找文件时使用本工具。""";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", Schemas.prop("string", "glob 模式，如 **/*.java"));
        props.put("path", Schemas.prop("string", "搜索根目录，默认当前工作目录"));
        return Schemas.object(props, List.of("pattern"));
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public boolean isConcurrencySafe() {
        return true;
    }

    @Override
    public ToolResult execute(AssistantMessage.ToolCall call, ToolContext ctx) {
        String pattern = ToolCallHelper.getString(call, "pattern", "").trim();
        if (pattern.isEmpty()) {
            return ToolResult.error("缺少必填参数 pattern。");
        }

        Path base = ctx.workingDir();
        String pathArg = ToolCallHelper.getString(call, "path", "");
        if (!pathArg.isBlank()) {
            Path p = Path.of(pathArg);
            base = p.isAbsolute() ? p : base.resolve(p);
        }
        final Path root = base;

        // glob 匹配相对于搜索根目录的相对路径，这样 ** 的行为符合直觉。
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        List<Path> matched = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(GlobTool::notIgnored)
                    .forEach(file -> {
                        Path relative = root.relativize(file);
                        if (matcher.matches(relative)) {
                            matched.add(file);
                        }
                    });
        } catch (IOException e) {
            return ToolResult.error("查找失败：" + e.getMessage());
        }

        if (matched.isEmpty()) {
            return ToolResult.ok("没有匹配的文件。");
        }

        // 按修改时间倒序排列（最近修改的在前）。
        matched.sort(Comparator.comparingLong(GlobTool::lastModified).reversed());

        StringBuilder sb = new StringBuilder();
        int count = Math.min(matched.size(), MAX_RESULTS);
        for (int i = 0; i < count; i++) {
            sb.append(matched.get(i)).append('\n');
        }
        if (matched.size() > MAX_RESULTS) {
            sb.append("<系统提醒：匹配到 ").append(matched.size())
                    .append(" 个文件，仅显示前 ").append(MAX_RESULTS).append(" 个。>");
        }
        return ToolResult.ok(sb.toString().stripTrailing());
    }

    /** 读取文件修改时间，失败则按 0 处理。 */
    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 跳过常见的无需查找的目录。 */
    private static boolean notIgnored(Path path) {
        for (Path part : path) {
            String s = part.toString();
            if (s.equals(".git") || s.equals("node_modules") || s.equals("build")
                    || s.equals("target") || s.equals(".gradle") || s.equals(".idea")) {
                return false;
            }
        }
        return true;
    }
}
