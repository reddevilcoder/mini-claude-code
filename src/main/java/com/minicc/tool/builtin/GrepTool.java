package com.minicc.tool.builtin;

import com.minicc.tool.Schemas;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Grep 工具：在代码库中按正则检索。
 *
 * <p>基于 ripgrep：优先调用系统的 rg 以保证性能与一致行为；
 * 当检测不到 rg 时，降级为基于 Java 正则 + 目录遍历的实现，保证"下载即可运行"。
 *
 * <p>M1 支持最常用的参数：pattern / path / glob / output_mode / -i。
 * 其余（type 过滤、上下文行、多行匹配等）留作后续扩展。
 */
public class GrepTool implements Tool {

    /** 单次检索返回的最大行数，避免结果过大淹没上下文。 */
    private static final int MAX_OUTPUT_LINES = 200;

    @Override
    public String name() {
        return "Grep";
    }

    @Override
    public String description() {
        return """
                基于 ripgrep 的强力检索工具。检索代码请始终使用本工具。
                - pattern 支持完整正则语法。
                - 用 glob 过滤文件（如 "*.java"）。
                - output_mode：content 显示匹配行、files_with_matches 只显示文件路径（默认）、count 显示匹配计数。
                - 置 -i 为 true 忽略大小写。""";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", Schemas.prop("string", "要检索的正则表达式"));
        props.put("path", Schemas.prop("string", "检索的目录或文件，默认当前工作目录"));
        props.put("glob", Schemas.prop("string", "按文件名模式过滤，如 *.java"));
        props.put("output_mode", Schemas.prop("string",
                "输出模式：content / files_with_matches / count，默认 files_with_matches",
                List.of("content", "files_with_matches", "count")));
        props.put("-i", Schemas.prop("boolean", "是否忽略大小写，默认 false"));
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
        String outputMode = ToolCallHelper.getString(call, "output_mode", "files_with_matches");
        String glob = ToolCallHelper.getString(call, "glob", "");
        boolean ignoreCase = ToolCallHelper.getBool(call, "-i", false);

        // 检索根目录：参数 path 优先，否则用工作目录；相对路径以工作目录为基准。
        Path base = ctx.workingDir();
        Path searchPath = base;
        String pathArg = ToolCallHelper.getString(call, "path", "");
        if (!pathArg.isBlank()) {
            Path p = Path.of(pathArg);
            searchPath = p.isAbsolute() ? p : base.resolve(p);
        }

        if (hasRipgrep()) {
            return runRipgrep(pattern, searchPath, glob, outputMode, ignoreCase);
        }
        return runJavaFallback(pattern, searchPath, glob, outputMode, ignoreCase);
    }

    // ---------- ripgrep 实现 ----------

    /** 检测系统是否可用 rg。 */
    private static boolean hasRipgrep() {
        try {
            Process p = new ProcessBuilder("rg", "--version").redirectErrorStream(true).start();
            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /** 调用 rg 完成检索。 */
    private ToolResult runRipgrep(String pattern, Path searchPath, String glob,
                                  String outputMode, boolean ignoreCase) {
        List<String> cmd = new ArrayList<>();
        cmd.add("rg");
        cmd.add("--color=never");
        switch (outputMode) {
            case "files_with_matches" -> cmd.add("-l");
            case "count" -> cmd.add("-c");
            default -> cmd.add("-n"); // content：带行号
        }
        if (ignoreCase) {
            cmd.add("-i");
        }
        if (!glob.isBlank()) {
            cmd.add("--glob");
            cmd.add(glob);
        }
        cmd.add(pattern);
        cmd.add(searchPath.toString());

        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = reader.lines().limit(MAX_OUTPUT_LINES + 1).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
            }
            int code = p.waitFor();
            // rg 约定：退出码 1 表示没有匹配（不是错误）。
            if (code == 1) {
                return ToolResult.ok("没有匹配结果。");
            }
            if (code != 0 && out.isBlank()) {
                return ToolResult.error("检索失败（rg 退出码 " + code + "）。");
            }
            return ToolResult.ok(truncate(out));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.error("检索失败：" + e.getMessage());
        }
    }

    // ---------- Java 降级实现 ----------

    /** 没有 rg 时的降级实现：遍历目录 + Java 正则。 */
    private ToolResult runJavaFallback(String pattern, Path searchPath, String glob,
                                       String outputMode, boolean ignoreCase) {
        final Pattern regex;
        try {
            regex = Pattern.compile(pattern, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("正则表达式非法：" + e.getMessage());
        }

        PathMatcher globMatcher = glob.isBlank()
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + glob);

        List<String> contentLines = new ArrayList<>();
        List<String> fileMatches = new ArrayList<>();
        int totalCount = 0;

        try (Stream<Path> stream = Files.walk(searchPath)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(GrepTool::notIgnored)
                    .toList();
            for (Path file : files) {
                if (globMatcher != null && !globMatcher.matches(file.getFileName())) {
                    continue;
                }
                int fileCount = 0;
                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (regex.matcher(lines.get(i)).find()) {
                            fileCount++;
                            totalCount++;
                            if (outputMode.equals("content") && contentLines.size() < MAX_OUTPUT_LINES) {
                                contentLines.add(file + ":" + (i + 1) + ":" + lines.get(i));
                            }
                        }
                    }
                } catch (IOException ignore) {
                    // 读不了（如二进制或无权限）就跳过该文件。
                    continue;
                }
                if (fileCount > 0) {
                    if (outputMode.equals("count")) {
                        fileMatches.add(file + ":" + fileCount);
                    } else {
                        fileMatches.add(file.toString());
                    }
                }
            }
        } catch (IOException e) {
            return ToolResult.error("检索失败：" + e.getMessage());
        }

        return switch (outputMode) {
            case "content" -> contentLines.isEmpty()
                    ? ToolResult.ok("没有匹配结果。")
                    : ToolResult.ok(truncate(String.join("\n", contentLines)));
            case "count" -> fileMatches.isEmpty()
                    ? ToolResult.ok("没有匹配结果。")
                    : ToolResult.ok(String.join("\n", fileMatches));
            default -> fileMatches.isEmpty()
                    ? ToolResult.ok("没有匹配结果。")
                    : ToolResult.ok(truncate(String.join("\n", fileMatches)));
        };
    }

    /** 跳过常见的无需检索的目录与隐藏文件。 */
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

    /** 超过上限的输出做截断并加提示。 */
    private static String truncate(String out) {
        String[] lines = out.split("\n", -1);
        if (lines.length <= MAX_OUTPUT_LINES) {
            return out;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_OUTPUT_LINES; i++) {
            sb.append(lines[i]).append('\n');
        }
        sb.append("<系统提醒：结果过多，仅显示前 ").append(MAX_OUTPUT_LINES)
                .append(" 行；请用更精确的 pattern 或 glob 缩小范围。>");
        return sb.toString();
    }
}
