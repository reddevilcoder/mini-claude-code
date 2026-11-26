package com.minicc.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 配置加载器：按"内置默认值 → 全局配置 → 项目级配置"的顺序层层覆盖，缺省时一律用默认值，
 * 保证"下载即可运行"，也不必每个项目都手建配置文件。
 *
 * <p>加载顺序（后者覆盖前者）：
 * <ol>
 *   <li>内置默认值（见 {@link MccConfig} 常量）；</li>
 *   <li>全局配置 {@code ~/.mcc/mcc.yml}：配一次，所有项目通用；</li>
 *   <li>项目级配置 {@code <项目目录>/.mcc/mcc.yml}：可选，仅当某个项目需要不同设置时才建。</li>
 * </ol>
 *
 * <p>API Key 等凭证不写入任何配置文件，而是从环境变量 OPENAI_API_KEY 读取；
 * base-url 可用 OPENAI_BASE_URL 覆盖。工具与具体模型厂商无关——使用 OpenAI 兼容接口这一事实标准，
 * 默认值只是开箱即用的一个选择，可随时改成任意 OpenAI 兼容服务。
 *
 * <p><b>面试关注：多层配置怎样合并？</b>
 * 用一个 Settings 累加器，依次应用全局和项目文件；后读取的文件只覆盖它提供的字段。
 * compact / skills 子配置按字段继承，toolResultBudget 按工具名合并，不是整个对象一刀切替换。
 * 最后环境变量只提供 API Key 与服务地址，并非每个配置项都有对应的环境变量覆盖。
 *
 * <p><b>面试追问：容错解析是否等于配置校验？</b>
 * 当前多数数字在解析失败时沿用旧值，但可解析的负数、错误的阈值顺序并没有统一校验；
 * Boolean.parseBoolean 对不认识的字符串返回 false，也不一定回退到原值。
 * 文件应用过程逐字段修改累加器，中途异常不会撤销已经应用的字段，因此不是事务式加载。
 * 这是便于本地 CLI 使用的取舍；若部署为服务，可增加范围/关联校验，并让错误配置明确失败。
 *
 * <p><b>面试关注：为什么暴露可测试内核？</b>
 * 显式传入全局配置路径能让测试使用临时目录，避免修改真实用户配置；
 * 环境变量仍由 System.getenv 读取，测试结果仍可能受调用进程的环境影响。
 */
public final class ConfigLoader {

    private ConfigLoader() {
    }

    /** 从指定工作目录加载配置（叠加全局配置 ~/.mcc/mcc.yml 与项目级配置）。 */
    public static MccConfig load(Path workingDir) {
        return load(workingDir, globalConfigPath());
    }

    /**
     * 可测试内核：显式传入全局配置路径，便于单元测试隔离真实的 ~/.mcc/mcc.yml。
     * 加载顺序：内置默认值 → 全局配置 → 项目级配置 → 环境变量。
     */
    static MccConfig load(Path workingDir, Path globalConfig) {
        // 1) 内置默认值。
        Settings s = new Settings();
        // 2) 全局配置覆盖默认值。
        applyFile(globalConfig, s);
        // 3) 项目级配置覆盖全局配置（可选）。
        applyFile(projectConfigPath(workingDir), s);

        // 凭证与服务地址来自环境变量（base-url 环境变量优先于配置文件）。
        // 使用 OpenAI 兼容接口的事实标准变量 OPENAI_API_KEY，不绑定特定厂商。
        String apiKey = System.getenv("OPENAI_API_KEY");
        String baseUrl = firstNonBlank(System.getenv("OPENAI_BASE_URL"), s.baseUrl);

        return new MccConfig(s.model, s.maxSteps, s.contextLimit, apiKey, baseUrl,
                s.sandbox, s.bashTimeoutMs, s.stateDir, s.compact, s.toolResultBudget, s.skills, s.modelSettings);
    }

    /** 全局配置路径：~/.mcc/mcc.yml。 */
    public static Path globalConfigPath() {
        return Path.of(System.getProperty("user.home"), ".mcc", "mcc.yml");
    }

    /** 项目级配置路径：{@code <项目目录>/.mcc/mcc.yml}。 */
    public static Path projectConfigPath(Path workingDir) {
        return workingDir.resolve(".mcc").resolve("mcc.yml");
    }

    /**
     * 首次启动引导：如果全局配置 {@code ~/.mcc/mcc.yml} 不存在，就把内置的默认配置模板拷贝过去，
     * 让用户有一份带注释的文件可以直接修改。
     *
     * @return 新生成的文件路径；若已存在或生成失败则返回 null。
     */
    public static Path ensureGlobalConfig() {
        return ensureConfigFile(globalConfigPath());
    }

    /**
     * 引导逻辑的可测试内核：若 target 不存在，则从资源 default-mcc.yml 拷贝一份过去。
     *
     * @return 新生成的文件路径；若已存在或生成失败则返回 null。
     */
    static Path ensureConfigFile(Path target) {
        if (Files.exists(target)) {
            return null;
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            try (InputStream in = ConfigLoader.class.getResourceAsStream("/default-mcc.yml")) {
                if (in != null) {
                    Files.copy(in, target);
                } else {
                    // 资源缺失时的兜底：写一份最小默认配置。
                    Files.writeString(target, "model: " + MccConfig.DEFAULT_MODEL + "\n"
                            + "maxSteps: " + MccConfig.DEFAULT_MAX_STEPS + "\n"
                            + "contextLimit: " + MccConfig.DEFAULT_CONTEXT_LIMIT + "\n");
                }
            }
            return target;
        } catch (IOException e) {
            System.err.println("生成默认配置失败（" + target + "）：" + e.getMessage());
            return null;
        }
    }

    /** 若文件存在，就把其中出现的键覆盖进 settings；文件不存在或出错则跳过。 */
    private static void applyFile(Path file, Settings s) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> yaml = new Yaml().load(in);
            if (yaml != null) {
                s.model = asString(yaml.get("model"), s.model);
                s.maxSteps = asInt(yaml.get("maxSteps"), s.maxSteps);
                s.contextLimit = asInt(yaml.get("contextLimit"), s.contextLimit);
                s.baseUrl = asString(yaml.get("baseUrl"), s.baseUrl);
                s.sandbox = asBool(yaml.get("sandbox"), s.sandbox);
                s.bashTimeoutMs = asLong(yaml.get("bashTimeoutMs"), s.bashTimeoutMs);
                s.stateDir = asString(yaml.get("stateDir"), s.stateDir);
                // M6：compact（分层压缩阈值）与 toolResultBudget（各工具落盘字符阈值），都容错回落默认值。
                s.compact = asCompact(yaml.get("compact"), s.compact);
                s.toolResultBudget = asToolResultBudget(yaml.get("toolResultBudget"), s.toolResultBudget);
                // M7：skills（开关 + 清单预算），缺省回落默认值。
                s.skills = asSkills(yaml.get("skills"), s.skills);
                // M9：流式开关、重试策略与计价表。
                s.modelSettings = asModelSettings(yaml, s.modelSettings);
            }
        } catch (Exception e) {
            // 配置文件读取失败不致命，保留累加器当前值并提示；前面已成功赋值的字段不会回滚。
            System.err.println("读取配置失败（" + file + "），已忽略：" + e.getMessage());
        }
    }

    /** 可变的配置累加器，字段初始化为内置默认值。 */
    private static final class Settings {
        String model = MccConfig.DEFAULT_MODEL;
        int maxSteps = MccConfig.DEFAULT_MAX_STEPS;
        int contextLimit = MccConfig.DEFAULT_CONTEXT_LIMIT;
        String baseUrl = MccConfig.DEFAULT_BASE_URL;
        boolean sandbox = MccConfig.DEFAULT_SANDBOX;
        long bashTimeoutMs = MccConfig.DEFAULT_BASH_TIMEOUT_MS;
        String stateDir = MccConfig.DEFAULT_STATE_DIR;
        MccConfig.CompactSettings compact = MccConfig.DEFAULT_COMPACT;      // M6
        Map<String, Integer> toolResultBudget = MccConfig.DEFAULT_TOOL_RESULT_BUDGET;  // M6
        MccConfig.SkillSettings skills = MccConfig.DEFAULT_SKILLS;          // M7
        MccConfig.ModelSettings modelSettings = MccConfig.DEFAULT_MODEL_SETTINGS; // M9
    }

    /**
     * M9：读取顶层的 {@code stream}、{@code retry} 段与 {@code pricing} 段。
     * pricing 按“模型名 → {input, output, cacheRead}”合并：项目级只覆盖它写到的模型。
     */
    @SuppressWarnings("unchecked")
    private static MccConfig.ModelSettings asModelSettings(Map<String, Object> yaml, MccConfig.ModelSettings base) {
        boolean stream = asBool(yaml.get("stream"), base.stream());
        int attempts = base.retryMaxAttempts();
        long backoff = base.retryInitialBackoffMs();
        if (yaml.get("retry") instanceof Map<?, ?> r) {
            attempts = Math.max(1, asInt(r.get("maxAttempts"), attempts));
            backoff = Math.max(0, asLong(r.get("initialBackoffMs"), backoff));
        }
        Map<String, MccConfig.Price> pricing = new java.util.LinkedHashMap<>(base.pricing());
        if (yaml.get("pricing") instanceof Map<?, ?> p) {
            for (Map.Entry<?, ?> e : p.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> m)) {
                    continue;
                }
                Double in = asDoubleOrNull(m.get("input"));
                Double out = asDoubleOrNull(m.get("output"));
                if (in == null || out == null) {
                    System.err.println("pricing." + e.getKey() + " 缺少 input/output，已忽略");
                    continue;
                }
                pricing.put(String.valueOf(e.getKey()), new MccConfig.Price(in, out, asDoubleOrNull(m.get("cacheRead"))));
            }
        }
        return new MccConfig.ModelSettings(stream, attempts, backoff, Map.copyOf(pricing));
    }

    private static Double asDoubleOrNull(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String str) {
            try {
                return Double.parseDouble(str.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 读取 {@code compact} 子配置（SnakeYAML 读出来是 Map，按 key 取值）。
     * 任何缺省字段都回落到传入的默认值 {@code base} 的对应字段，宽容处理。
     */
    @SuppressWarnings("unchecked")
    private static MccConfig.CompactSettings asCompact(Object node, MccConfig.CompactSettings base) {
        if (!(node instanceof Map)) {
            return base;
        }
        Map<String, Object> m = (Map<String, Object>) node;
        double collapseAt = asDouble(m.get("collapseAt"), base.collapseAt());
        double autoCompactAt = asDouble(m.get("autoCompactAt"), base.autoCompactAt());
        int idle = asInt(m.get("microcompactIdleMinutes"), base.microcompactIdleMinutes());
        int keep = asInt(m.get("keepRecentToolResults"), base.keepRecentToolResults());
        return new MccConfig.CompactSettings(collapseAt, autoCompactAt, idle, keep);
    }

    /**
     * 读取 {@code skills} 子配置（M7：{@code enabled} + {@code listingMaxChars}）。
     * 任何缺省字段都回落到传入默认值 {@code base} 的对应字段，宽容处理。
     */
    @SuppressWarnings("unchecked")
    private static MccConfig.SkillSettings asSkills(Object node, MccConfig.SkillSettings base) {
        if (!(node instanceof Map)) {
            return base;
        }
        Map<String, Object> m = (Map<String, Object>) node;
        boolean enabled = asBool(m.get("enabled"), base.enabled());
        int listingMaxChars = asInt(m.get("listingMaxChars"), base.listingMaxChars());
        return new MccConfig.SkillSettings(enabled, listingMaxChars);
    }

    /**
     * 读取 {@code toolResultBudget} 子配置（工具名 → 字符阈值）。
     * 以默认值为底，用户给出的键覆盖；{@code -1} 表示该工具永不落盘。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Integer> asToolResultBudget(Object node, Map<String, Integer> base) {
        if (!(node instanceof Map)) {
            return base;
        }
        Map<String, Integer> merged = new java.util.HashMap<>(base);
        ((Map<String, Object>) node).forEach((k, v) -> {
            Integer n = asInt(v, null);
            if (n != null) {
                merged.put(k, n);
            }
        });
        return merged;
    }

    private static String asString(Object v, String defaultValue) {
        return v == null ? defaultValue : String.valueOf(v);
    }

    private static int asInt(Object v, int defaultValue) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 读整数，允许返回 null（供 toolResultBudget 逐项解析，非法项直接跳过）。 */
    private static Integer asInt(Object v, Integer defaultValue) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double asDouble(Object v, double defaultValue) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long asLong(Object v, long defaultValue) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean asBool(Object v, boolean defaultValue) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }
}
