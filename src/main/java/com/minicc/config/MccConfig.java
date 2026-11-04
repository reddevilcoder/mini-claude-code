package com.minicc.config;

import java.util.Map;

/**
 * 运行配置，对应全局 {@code ~/.mcc/mcc.yml}、项目级 {@code <项目目录>/.mcc/mcc.yml} 及若干环境变量。
 *
 * <p>M1 只用到 model / maxSteps / contextLimit。M2 新增 sandbox 与 bashTimeoutMs。
 * M4 新增 stateDir。M6 新增两组：{@link #compact}（分层压缩的触发阈值）与
 * {@link #toolResultBudget}（各工具结果落盘的字符阈值）。{@code contextLimit} 从 M1 起
 * "仅作记录"，到 M6 终于被真正用上——它是所有压缩百分比阈值的分母。
 *
 * @param model            默认模型名
 * @param maxSteps         单次任务的最大循环步数（边界保护）
 * @param contextLimit     上下文窗口上限（Token）；M6 起作为压缩阈值的分母
 * @param apiKey           模型 API Key（来自环境变量，不写入 mcc.yml）
 * @param baseUrl          模型服务地址（OpenAI 兼容接口）
 * @param sandbox          是否把 Bash 命令包进 OS 沙箱（M2）
 * @param bashTimeoutMs    Bash 默认超时（毫秒，M2）
 * @param stateDir         会话与记忆的状态根目录（M4；默认 ~/.mcc，tool-results/ 也挂在此根下）
 * @param compact          M6：分层压缩的触发阈值
 * @param toolResultBudget M6：各工具结果落盘阈值（字符数；{@code -1} 表示无限、永不落盘）
 * @param skills           M7：Skills 配置（开关 + 清单字符预算）
 */
public record MccConfig(
        String model,
        int maxSteps,
        int contextLimit,
        String apiKey,
        String baseUrl,
        boolean sandbox,
        long bashTimeoutMs,
        String stateDir,
        CompactSettings compact,
        Map<String, Integer> toolResultBudget,
        SkillSettings skills) {

    /**
     * M6：分层压缩的触发阈值（配置里的 {@code compact} 段）。
     *
     * @param collapseAt              上下文折叠触发比例（窗口占用 / contextLimit）
     * @param autoCompactAt           自动压缩触发比例（需要调用模型）
     * @param microcompactIdleMinutes 会话空闲多久（分钟）后才做微压缩
     * @param keepRecentToolResults   微压缩保留最近几次工具结果
     */
    public record CompactSettings(
            double collapseAt,
            double autoCompactAt,
            int microcompactIdleMinutes,
            int keepRecentToolResults) {
    }

    /**
     * M7：Skills 配置（配置里的 {@code skills} 段）。
     *
     * @param enabled         是否启用 Skills（默认开；关掉则不扫描目录、SkillStage 不注入）
     * @param listingMaxChars 技能清单在上下文里的字符上限（超出按预算截断；约占 1% 窗口）
     */
    public record SkillSettings(
            boolean enabled,
            int listingMaxChars) {
    }

    /**
     * 向后兼容构造器：保留 M1–M5 的 8 参签名，压缩相关 + Skills 字段用默认值补齐。
     * 便于既有测试与调用点不改签名即可编译。
     */
    public MccConfig(String model, int maxSteps, int contextLimit, String apiKey, String baseUrl,
                     boolean sandbox, long bashTimeoutMs, String stateDir) {
        this(model, maxSteps, contextLimit, apiKey, baseUrl, sandbox, bashTimeoutMs, stateDir,
                DEFAULT_COMPACT, DEFAULT_TOOL_RESULT_BUDGET, DEFAULT_SKILLS);
    }

    /**
     * 向后兼容构造器：保留 M6 的 10 参签名，Skills 字段用默认值补齐。
     */
    public MccConfig(String model, int maxSteps, int contextLimit, String apiKey, String baseUrl,
                     boolean sandbox, long bashTimeoutMs, String stateDir,
                     CompactSettings compact, Map<String, Integer> toolResultBudget) {
        this(model, maxSteps, contextLimit, apiKey, baseUrl, sandbox, bashTimeoutMs, stateDir,
                compact, toolResultBudget, DEFAULT_SKILLS);
    }

    /** 默认模型。 */
    public static final String DEFAULT_MODEL = "deepseek-flash";
    /** 默认最大步数。 */
    public static final int DEFAULT_MAX_STEPS = 40;
    /** 默认上下文上限。 */
    public static final int DEFAULT_CONTEXT_LIMIT = 200000;
    /** 默认服务地址（DeepSeek 的 OpenAI 兼容接口）。 */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    /** 默认启用沙箱。 */
    public static final boolean DEFAULT_SANDBOX = true;
    /** 默认 Bash 超时：2 分钟。 */
    public static final long DEFAULT_BASH_TIMEOUT_MS = 120_000;
    /** 默认状态根目录（M4）：~/.mcc。 */
    public static final String DEFAULT_STATE_DIR = "~/.mcc";

    // ---- M6 默认值 ----
    /** 默认折叠触发比例：90%。 */
    public static final double DEFAULT_COLLAPSE_AT = 0.90;
    /** 默认自动压缩触发比例：95%。 */
    public static final double DEFAULT_AUTO_COMPACT_AT = 0.95;
    /** 默认微压缩空闲阈值：60 分钟。 */
    public static final int DEFAULT_MICROCOMPACT_IDLE_MINUTES = 60;
    /** 默认微压缩保留最近工具结果数：5。 */
    public static final int DEFAULT_KEEP_RECENT_TOOL_RESULTS = 5;
    /** 配置里未列出的工具，其结果落盘的默认字符阈值。 */
    public static final int DEFAULT_TOOL_RESULT_CHARS = 50_000;

    /** 默认压缩阈值。 */
    public static final CompactSettings DEFAULT_COMPACT = new CompactSettings(
            DEFAULT_COLLAPSE_AT, DEFAULT_AUTO_COMPACT_AT,
            DEFAULT_MICROCOMPACT_IDLE_MINUTES, DEFAULT_KEEP_RECENT_TOOL_RESULTS);

    /** 默认各工具结果落盘阈值（字符数；Read=-1 永不落盘）。 */
    public static final Map<String, Integer> DEFAULT_TOOL_RESULT_BUDGET = Map.of(
            "Read", -1,
            "Bash", 30_000,
            "Grep", 20_000,
            "Edit", 100_000,
            "Write", 100_000,
            "Glob", 100_000);

    // ---- M7 默认值 ----
    /** 默认启用 Skills。 */
    public static final boolean DEFAULT_SKILLS_ENABLED = true;
    /** 默认技能清单字符上限：8000（约 1% × 200k × 4）。 */
    public static final int DEFAULT_SKILLS_LISTING_MAX_CHARS = 8_000;
    /** 默认 Skills 配置。 */
    public static final SkillSettings DEFAULT_SKILLS = new SkillSettings(
            DEFAULT_SKILLS_ENABLED, DEFAULT_SKILLS_LISTING_MAX_CHARS);

    // ---- M6 便捷访问器（让压缩模块的代码读起来更直观）----

    /** 折叠触发比例。 */
    public double collapseAt() {
        return compact.collapseAt();
    }

    /** 自动压缩触发比例。 */
    public double autoCompactAt() {
        return compact.autoCompactAt();
    }

    /** 微压缩空闲阈值（分钟）。 */
    public int microcompactIdleMinutes() {
        return compact.microcompactIdleMinutes();
    }

    /** 微压缩保留最近工具结果数。 */
    public int keepRecentToolResults() {
        return compact.keepRecentToolResults();
    }
}
