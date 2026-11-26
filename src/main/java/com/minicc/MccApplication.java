package com.minicc;

import com.minicc.checkpoint.CheckpointStore;

import com.minicc.agent.AgentLoop;
import com.minicc.agent.CancelToken;
import com.minicc.agent.RunResult;
import com.minicc.ask.QuestionAsker;
import com.minicc.cli.CardPrompter;
import com.minicc.cli.CliApprover;
import com.minicc.cli.CliPlanApprover;
import com.minicc.cli.CliQuestionAsker;
import com.minicc.cli.Renderer;
import com.minicc.cli.Repl;
import com.minicc.compact.AutoCompactor;
import com.minicc.compact.CompactionManager;
import com.minicc.compact.ContextCollapser;
import com.minicc.compact.MicroCompactor;
import com.minicc.compact.SnipCompactor;
import com.minicc.compact.ToolResultCompactor;
import com.minicc.cli.command.McpCommand;
import com.minicc.mcp.McpConfig;
import com.minicc.mcp.McpManager;
import com.minicc.config.ConfigLoader;
import com.minicc.config.MccConfig;
import com.minicc.context.CompactionStage;
import com.minicc.context.ContextAssembler;
import com.minicc.context.MemoryStage;
import com.minicc.context.PlanModeStage;
import com.minicc.context.SkillStage;
import com.minicc.context.SystemPrompt;
import com.minicc.context.SystemPromptStage;
import com.minicc.headless.HeadlessInteraction;
import com.minicc.headless.HeadlessOptions;
import com.minicc.headless.HeadlessReport;
import com.minicc.memory.AgentMemory;
import com.minicc.memory.AutoMemory;
import com.minicc.memory.MemoryExtractor;
import com.minicc.model.ModelClient;
import com.minicc.permission.ApprovalHook;
import com.minicc.permission.Approver;
import com.minicc.persistence.SessionPaths;
import com.minicc.persistence.SessionStore;
import com.minicc.persistence.TodoSnapshotStore;
import com.minicc.plan.PlanApprover;
import com.minicc.plan.PlanModeHook;
import com.minicc.session.Session;
import com.minicc.skill.Skill;
import com.minicc.skill.SkillLoader;
import com.minicc.tool.ToolRegistry;
import com.minicc.tool.bash.Sandbox;
import com.minicc.tool.builtin.AskUserQuestionTool;
import com.minicc.tool.builtin.BashTool;
import com.minicc.tool.builtin.EditTool;
import com.minicc.tool.builtin.EnterPlanModeTool;
import com.minicc.tool.builtin.ExitPlanModeTool;
import com.minicc.tool.builtin.GlobTool;
import com.minicc.tool.builtin.GrepTool;
import com.minicc.tool.builtin.ReadTool;
import com.minicc.tool.builtin.TodoWriteTool;
import com.minicc.tool.builtin.WriteTool;
import com.minicc.tool.hook.HookChain;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 程序入口：手动把各个组件装配起来，然后启动交互式会话（REPL）或执行一次 Headless 任务（M0）。
 *
 * <p>这里刻意不使用 Spring Boot 的自动装配，而是一行行把依赖 new 出来并串起来，
 * 让学员清楚地看到 harness 的每一部分是怎么组合的，以及 Spring AI 到底承担了哪一小块。
 *
 * <p><b>面试讲解入口：按这条依赖链介绍项目。</b>
 * <ol>
 *   <li>{@link Repl} 接收用户输入，{@link Session} 保存当前会话状态。</li>
 *   <li>{@link ContextAssembler} 组装提示词、记忆、技能与压缩后的历史。</li>
 *   <li>{@link ModelClient} 完成一次模型调用，{@link AgentLoop} 决定继续执行工具还是结束本轮。</li>
 *   <li>ToolScheduler 按工具能力调度，每个工具执行前通过 {@link HookChain}；结果进入历史，形成下一步的观察。</li>
 *   <li>{@link SessionStore} 保存消息日志，{@link CompactionManager} 控制上下文体积，
 *       {@link MemoryExtractor} 在适当时机把可复用信息保存到长期记忆。</li>
 * </ol>
 *
 * <p><b>M0：两种入口共用一套装配。</b>交互模式与 Headless 模式的差别只在"人机交互部件"
 * （审批 / 提问 / 计划确认）与渲染输出目标；控制循环、工具、上下文管线完全相同——
 * 这正是 Eval 测得的结果能代表交互模式能力的前提。
 *
 * <p><b>面试追问：提示词、审批和沙箱分别解决什么？</b>
 * 提示词引导模型行为，Hook 在本地执行前落实规则，OS 沙箱限制子进程实际可用资源。
 * Headless + 自动批准时没有人在看，因此沙箱切换为 fail-closed：不可用就拒绝执行，而不是降级裸跑。
 */
public class MccApplication {

    private static final String VERSION = "v0.2";

    /** 交互部件：审批、提问、计划确认。交互模式用卡片实现，Headless 用无人值守实现。 */
    private record Interaction(Approver approver, QuestionAsker asker, PlanApprover planApprover) {
    }

    /** 装配完成后供两种入口使用的组件。 */
    private record Harness(AgentLoop agentLoop, Session session, ToolRegistry registry, ModelClient modelClient,
                           SessionPaths paths, Path stateDir, AutoMemory autoMemory,
                           CompactionManager compactionManager, MemoryStage memoryStage,
                           List<Skill> skills, boolean skillsEnabled, McpManager mcp) {
    }

    public static void main(String[] args) {
        HeadlessOptions options;
        try {
            options = HeadlessOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误：" + e.getMessage());
            System.err.println(HeadlessOptions.USAGE);
            System.exit(64);
            return;
        }
        if (options.help()) {
            System.out.println(HeadlessOptions.USAGE);
            return;
        }
        if (options.isHeadless()) {
            System.exit(runHeadless(options));
        } else {
            runInteractive(options);
        }
    }

    // ------------------------------------------------------------------ 交互模式

    private static void runInteractive(HeadlessOptions options) {
        Path workingDir = options.workingDir();

        // 首次启动引导：若全局配置不存在，自动生成一份带注释的默认配置供用户修改。
        Path created = ConfigLoader.ensureGlobalConfig();
        if (created != null) {
            System.out.println("已生成默认配置：" + created + "（可按需修改）");
        }
        MccConfig config = options.applyTo(ConfigLoader.load(workingDir));
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            System.err.println("提示：未检测到 API Key，请设置环境变量 OPENAI_API_KEY 后再对话。");
        }

        // 终端在这里统一创建、全程共用：LineReader（回合间输入）与 CardPrompter（审批/提问卡片）都用它。
        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            System.err.println("初始化终端失败：" + e.getMessage());
            return;
        }
        CancelToken cancelToken = new CancelToken();
        CardPrompter prompter = new CardPrompter(terminal);
        Interaction interaction = new Interaction(
                new CliApprover(prompter, cancelToken),
                new CliQuestionAsker(prompter, cancelToken),
                new CliPlanApprover(prompter, cancelToken));
        Renderer renderer = new Renderer();

        Harness rt = assemble(workingDir, config, interaction, renderer, cancelToken, true, true);

        Repl repl = new Repl(rt.agentLoop(), rt.session(), rt.registry(), renderer, terminal, cancelToken,
                rt.paths(), prompter, rt.stateDir(), rt.autoMemory(), VERSION, config.model(),
                config, rt.compactionManager(), rt.memoryStage(),
                rt.skills(), rt.skillsEnabled(), rt.modelClient().usage());
        repl.registerCommand(new McpCommand(rt.mcp(), renderer));                  // M11
        try {
            repl.start();
        } finally {
            rt.mcp().close();
            try {
                terminal.close();
            } catch (IOException ignore) {
                // 退出时关闭失败无妨。
            }
        }
    }

    // ------------------------------------------------------------------ Headless 模式（M0）

    /**
     * 执行一次非交互任务：stdout 只输出最终结果（text 或 JSON），过程渲染全部走 stderr。
     *
     * @return 进程退出码：0 完成、1 出错、2 触达步数上限、130 被取消、78 配置缺失
     */
    static int runHeadless(HeadlessOptions options) {
        long startNanos = System.nanoTime();
        Path workingDir = options.workingDir();
        MccConfig config = options.applyTo(ConfigLoader.load(workingDir));
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            System.err.println("未检测到 API Key，请设置环境变量 OPENAI_API_KEY。");
            return 78;
        }

        // fail-closed：没人值守又自动放行写操作时，沙箱不可用必须拒绝执行 Bash。
        boolean autoApprove = options.permission() == HeadlessInteraction.PermissionMode.AUTO;
        if (autoApprove && config.sandbox()) {
            Sandbox.setStrict(true);
        }

        HeadlessInteraction headless = new HeadlessInteraction(options.permission());
        CancelToken cancelToken = new CancelToken();
        // Ctrl-C / SIGTERM：置位取消，让循环在检查点安全收尾（日志已逐条落盘）。
        Thread hook = new Thread(cancelToken::cancel, "mcc-cancel");
        Runtime.getRuntime().addShutdownHook(hook);

        Renderer renderer = new Renderer(options.quiet() ? nullStream() : System.err);
        Harness rt = assemble(workingDir, config, new Interaction(headless, headless, headless),
                renderer, cancelToken, !options.bare(), !options.bare());

        rt.session().appendUserMessage(options.prompt());
        RunResult result;
        try {
            result = rt.agentLoop().run(rt.session());
        } finally {
            rt.mcp().close();
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

        HeadlessReport report = HeadlessReport.of(result, rt.session().sessionId(), config.model(),
                durationMs, rt.modelClient().usage(), config.price());
        if (options.jsonOutput()) {
            System.out.println(report.toJson());
        } else if (result.finalText() != null) {
            System.out.println(result.finalText().strip());
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignore) {
            // JVM 已在关闭中。
        }
        return report.exitCode();
    }

    private static java.io.PrintStream nullStream() {
        return new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
    }

    // ------------------------------------------------------------------ 共用装配

    /**
     * 装配 harness 的全部核心组件。两种入口唯一的差别在 {@code interaction} 与 {@code renderer}。
     *
     * @param enableMemoryExtraction 是否在轮末做记忆补充提取（Headless --bare 关闭，避免额外模型调用干扰评测）
     * @param enableSkills           是否扫描 Skills（--bare 关闭，避免本机全局技能影响评测可复现性）
     */
    private static Harness assemble(Path workingDir, MccConfig config, Interaction interaction, Renderer renderer,
                                    CancelToken cancelToken, boolean enableMemoryExtraction, boolean enableSkills) {
        // 1) 注册工具。
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadTool());
        registry.register(new GrepTool());
        registry.register(new GlobTool());
        registry.register(new EditTool());
        registry.register(new WriteTool());
        registry.register(new BashTool());
        registry.register(new TodoWriteTool());
        registry.register(new AskUserQuestionTool(interaction.asker()));
        registry.register(new EnterPlanModeTool(interaction.planApprover()));
        registry.register(new ExitPlanModeTool(interaction.planApprover()));

        // 1.5) MCP（M11）：只启动 ~/.mcc/mcp.json 白名单里的 Server；--bare 不加载，保证评测可复现。
        //      必须在计算工具结果预算之前注册，MCP 大结果才会走 ToolResultCompactor。
        McpManager mcp = new McpManager(
                enableSkills ? McpConfig.load(McpConfig.defaultPath()) : new McpConfig.Loaded(List.of(), List.of()),
                registry, McpManager.stdioFactory(workingDir, resolveStateDir(config.stateDir())));
        mcp.connectAll();
        for (var h : mcp.servers()) {
            if (!h.status().startsWith("已连接")) {
                renderer.warn("MCP " + h.name() + "：" + h.status());
            }
        }
        mcp.configErrors().forEach(e -> renderer.warn("MCP 配置：" + e));

        // 2) 长期记忆（M5）。记忆目录挂在 stateDir 结构下（~/.mcc/projects/<项目>/memory/）。
        Path stateDir = resolveStateDir(config.stateDir());
        SessionPaths paths = new SessionPaths(stateDir);
        Path memoryDir = paths.memoryDir(workingDir);
        AgentMemory agentMemory = new AgentMemory(workingDir, stateDir);
        AutoMemory autoMemory = new AutoMemory(memoryDir);
        autoMemory.ensureMemoryDirExists();

        // 3) 模型边界与钩子链：PlanModeHook（现在能不能写）排在 ApprovalHook（这次写准不准）之前。
        ModelClient modelClient = new ModelClient(config);
        HookChain hookChain = new HookChain(List.of(
                new PlanModeHook(),
                new ApprovalHook(interaction.approver())));

        // 4) 分层压缩（M6）。
        Map<String, Integer> budgetByTool = new HashMap<>(config.toolResultBudget());
        for (var tool : registry.all()) {
            budgetByTool.putIfAbsent(tool.name(), tool.maxResultChars());
        }
        ToolResultCompactor toolResultCompactor = new ToolResultCompactor(
                paths.toolResultsDir(workingDir), budgetByTool, MccConfig.DEFAULT_TOOL_RESULT_CHARS);
        CompactionManager compactionManager = new CompactionManager(
                toolResultCompactor,
                new SnipCompactor(),
                new MicroCompactor(config),
                new ContextCollapser(config),
                new AutoCompactor(modelClient, renderer),
                config, renderer);

        // 5) Skills（M7）。
        MccConfig.SkillSettings skillSettings = config.skills();
        boolean skillsEnabled = enableSkills && skillSettings.enabled();
        List<Skill> skills;
        if (skillsEnabled) {
            Path globalSkillsDir = Path.of(System.getProperty("user.home"), ".mcc", "skills");
            Path projectSkillsDir = workingDir.resolve(".mcc").resolve("skills");
            SkillLoader skillLoader = new SkillLoader(globalSkillsDir, projectSkillsDir);
            skillLoader.ensureGlobalDirExists();
            skills = skillLoader.loadAll();
        } else {
            skills = List.of();
        }

        // 6) 上下文管线：系统提示词 → 记忆 → Skills → 计划态提示 → 压缩后的会话历史。
        MemoryStage memoryStage = new MemoryStage(agentMemory, autoMemory);
        SkillStage skillStage = new SkillStage(skills, skillSettings.listingMaxChars());
        ContextAssembler assembler = new ContextAssembler(List.of(
                new SystemPromptStage(SystemPrompt.TEXT),
                memoryStage,
                skillStage,
                new PlanModeStage(),
                new CompactionStage(compactionManager)));

        // 7) 轮末记忆提取（M5）：只读工具 + Edit/Write，写入由 memoryWritesOnly 约束到记忆目录。
        MemoryExtractor memoryExtractor = null;
        if (enableMemoryExtraction) {
            ToolRegistry memoryRegistry = new ToolRegistry();
            memoryRegistry.register(new ReadTool());
            memoryRegistry.register(new GrepTool());
            memoryRegistry.register(new GlobTool());
            memoryRegistry.register(new EditTool());
            memoryRegistry.register(new WriteTool());
            memoryExtractor = new MemoryExtractor(modelClient, memoryRegistry, hookChain, autoMemory, config, renderer);
        }

        AgentLoop agentLoop = new AgentLoop(modelClient, assembler, registry, hookChain, renderer,
                config, cancelToken, memoryDir, memoryExtractor, compactionManager);

        // 8) 会话持久化（M4）：每次 append* 都顺手落盘。
        Session session = new Session(workingDir);
        String sessionId = UUID.randomUUID().toString();
        SessionStore store = new SessionStore(paths.sessionLog(workingDir, sessionId));
        TodoSnapshotStore todoStore = new TodoSnapshotStore(paths.todoSnapshot(workingDir, sessionId));
        session.attachPersistence(sessionId, store, todoStore,
                new CheckpointStore(paths.checkpointDir(workingDir, sessionId)));   // M10

        return new Harness(agentLoop, session, registry, modelClient, paths, stateDir, autoMemory,
                compactionManager, memoryStage, skills, skillsEnabled, mcp);
    }

    /** 把配置里的 stateDir 解析成绝对路径：展开前导 {@code ~} 为用户主目录（默认 ~/.mcc）。 */
    private static Path resolveStateDir(String stateDir) {
        String value = (stateDir == null || stateDir.isBlank()) ? MccConfig.DEFAULT_STATE_DIR : stateDir.trim();
        String home = System.getProperty("user.home");
        if (value.equals("~")) {
            value = home;
        } else if (value.startsWith("~/")) {
            value = home + value.substring(1);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }
}
