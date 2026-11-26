package com.minicc.headless;

import com.minicc.config.MccConfig;

import java.nio.file.Path;

/**
 * 命令行参数（M0）。不传 {@code -p} 时进入交互模式，与旧版 {@code mcc [目录]} 用法兼容。
 *
 * <p>手写解析而不引入 Picocli：参数不多，保持"核心全部手写、依赖最少"的项目约定。
 */
public record HeadlessOptions(
        String prompt,
        Path workingDir,
        boolean jsonOutput,
        HeadlessInteraction.PermissionMode permission,
        Integer maxSteps,
        String model,
        String stateDir,
        boolean bare,
        boolean quiet,
        boolean help) {

    public static final String USAGE = """
            用法：
              mcc [目录]                         交互模式（默认）
              mcc -p "<需求>" [选项]             非交互（Headless）模式，执行一次任务后退出

            Headless 选项：
              -p, --prompt <text>                要执行的需求（必填，"-" 表示从 stdin 读取）
              -d, --dir <path>                   项目目录（默认当前目录）
                  --output text|json             输出格式（默认 text；json 输出结构化结果）
                  --permission readonly|auto     写操作策略（默认 readonly 全部拒绝；auto 全部放行，
                                                 此时沙箱 fail-closed：不可用即拒绝执行 Bash）
                  --max-steps <n>                覆盖 maxSteps
                  --model <name>                 覆盖模型
                  --state-dir <path>             覆盖状态目录（评测时用临时目录隔离记忆与会话）
                  --bare                         不加载 Skills、不做轮末记忆提取（评测可复现）
                  -q, --quiet                    不输出过程渲染（stderr）
              -h, --help                         显示帮助

            退出码：0 完成 · 1 出错 · 2 达到步数上限 · 130 被取消 · 64 参数错误 · 78 缺少 API Key
            """;

    public boolean isHeadless() {
        return prompt != null;
    }

    /** 解析参数；非法参数抛 {@link IllegalArgumentException}。 */
    public static HeadlessOptions parse(String[] args) {
        String prompt = null;
        Path dir = null;
        boolean json = false;
        HeadlessInteraction.PermissionMode permission = HeadlessInteraction.PermissionMode.READONLY;
        Integer maxSteps = null;
        String model = null;
        String stateDir = null;
        boolean bare = false;
        boolean quiet = false;
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-p", "--prompt" -> prompt = value(args, ++i, a);
                case "-d", "--dir" -> dir = Path.of(value(args, ++i, a));
                case "--output" -> {
                    String v = value(args, ++i, a);
                    if (!v.equals("json") && !v.equals("text")) {
                        throw new IllegalArgumentException("--output 只支持 text 或 json");
                    }
                    json = v.equals("json");
                }
                case "--permission" -> {
                    String v = value(args, ++i, a);
                    permission = switch (v) {
                        case "readonly" -> HeadlessInteraction.PermissionMode.READONLY;
                        case "auto" -> HeadlessInteraction.PermissionMode.AUTO;
                        default -> throw new IllegalArgumentException("--permission 只支持 readonly 或 auto");
                    };
                }
                case "--max-steps" -> {
                    try {
                        maxSteps = Integer.parseInt(value(args, ++i, a));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--max-steps 需要整数");
                    }
                    if (maxSteps <= 0) {
                        throw new IllegalArgumentException("--max-steps 必须大于 0");
                    }
                }
                case "--model" -> model = value(args, ++i, a);
                case "--state-dir" -> stateDir = value(args, ++i, a);
                case "--bare" -> bare = true;
                case "-q", "--quiet" -> quiet = true;
                case "-h", "--help" -> help = true;
                default -> {
                    if (a.startsWith("-")) {
                        throw new IllegalArgumentException("未知选项：" + a);
                    }
                    if (dir != null) {
                        throw new IllegalArgumentException("只能指定一个项目目录");
                    }
                    dir = Path.of(a);   // 兼容旧用法：mcc <目录>
                }
            }
        }
        if ("-".equals(prompt)) {
            try {
                prompt = new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("读取 stdin 失败：" + e.getMessage());
            }
        }
        if (prompt != null && prompt.isBlank()) {
            throw new IllegalArgumentException("--prompt 不能为空");
        }
        Path workingDir = (dir != null ? dir : Path.of(System.getProperty("user.dir"))).toAbsolutePath().normalize();
        return new HeadlessOptions(prompt, workingDir, json, permission, maxSteps, model, stateDir, bare, quiet, help);
    }

    /** 把命令行覆盖项叠加到配置上（命令行优先级最高）。 */
    public MccConfig applyTo(MccConfig c) {
        return new MccConfig(
                model != null ? model : c.model(),
                maxSteps != null ? maxSteps : c.maxSteps(),
                c.contextLimit(), c.apiKey(), c.baseUrl(), c.sandbox(), c.bashTimeoutMs(),
                stateDir != null ? stateDir : c.stateDir(),
                c.compact(), c.toolResultBudget(), c.skills(), c.modelSettings());
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " 缺少参数值");
        }
        return args[i];
    }
}
