package com.minicc;

import com.minicc.agent.AgentLoop;
import com.minicc.agent.CancelToken;
import com.minicc.cli.CardPrompter;
import com.minicc.cli.CliApprover;
import com.minicc.cli.CliQuestionAsker;
import com.minicc.cli.Renderer;
import com.minicc.cli.Repl;
import com.minicc.config.ConfigLoader;
import com.minicc.config.MccConfig;
import com.minicc.compact.CompactionManager;
import com.minicc.context.CompactionStage;
import com.minicc.context.ContextAssembler;
import com.minicc.context.MemoryStage;
import com.minicc.context.PlanModeStage;
import com.minicc.context.SkillStage;
import com.minicc.context.SystemPrompt;
import com.minicc.context.SystemPromptStage;
import com.minicc.compact.AutoCompactor;
import com.minicc.compact.ContextCollapser;
import com.minicc.compact.MicroCompactor;
import com.minicc.compact.SnipCompactor;
import com.minicc.compact.ToolResultCompactor;
import com.minicc.memory.AgentMemory;
import com.minicc.memory.AutoMemory;
import com.minicc.memory.MemoryExtractor;
import com.minicc.model.ModelClient;
import com.minicc.ask.QuestionAsker;
import com.minicc.permission.ApprovalHook;
import com.minicc.permission.Approver;
import com.minicc.plan.PlanApprover;
import com.minicc.plan.PlanModeHook;
import com.minicc.cli.CliPlanApprover;
import com.minicc.persistence.SessionPaths;
import com.minicc.persistence.SessionStore;
import com.minicc.persistence.TodoSnapshotStore;
import com.minicc.session.Session;
import com.minicc.skill.Skill;
import com.minicc.skill.SkillLoader;
import com.minicc.tool.ToolRegistry;
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
 * 程序入口：手动把各个组件装配起来，然后启动交互式会话（REPL）。
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
 * <p><b>面试关注：不用 Spring Boot，是否就没有依赖注入？</b>
 * 这里是手动的构造器注入：入口负责创建和连接对象，业务组件通过参数接收依赖。
 * 这是对象装配的集中位置，不应把每个组件的业务判断也搬到入口；测试仍可替换模型、审批器等依赖。
 *
 * <p><b>面试追问：提示词、审批和沙箱分别解决什么？</b>
 * 提示词引导模型行为，Hook 在本地执行前落实规则，OS 沙箱限制子进程实际可用资源。
 * 三者在不同层面生效，具体保证受工具实现与平台支持约束，不能把一句提示词当作权限隔离。
 * 当前按单终端、单活跃任务装配；若扩展多用户服务，需要明确每个 Session 的状态隔离、
 * 共享组件的线程安全和线程池/终端等资源的生命周期。
 */
public class MccApplication {

    private static final String VERSION = "v0.1";

    public static void main(String[] args) {
        // 1) 确定工作目录：参数优先，否则取进程当前目录。
        Path workingDir = (args.length > 0 ? Path.of(args[0]) : Path.of(System.getProperty("user.dir")))
                .toAbsolutePath().normalize();

        // 2) 首次启动引导：若全局配置不存在，自动生成一份带注释的默认配置供用户修改。
        Path created = ConfigLoader.ensureGlobalConfig();
        if (created != null) {
            System.out.println("已生成默认配置：" + created + "（可按需修改）");
        }

        // 3) 加载配置（内置默认值 → 全局 ~/.mcc/mcc.yml → 项目级 <项目>/.mcc/mcc.yml → 环境变量）。
        MccConfig config = ConfigLoader.load(workingDir);
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            System.err.println("提示：未检测到 API Key，请设置环境变量 OPENAI_API_KEY 后再对话。");
        }

        // 4) 终端与 M3 的人机交互部件。终端在这里统一创建、全程共用：
        //    LineReader（回合间输入）与 CardPrompter（审批/提问卡片）都用它。
        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            System.err.println("初始化终端失败：" + e.getMessage());
            return;
        }
        CancelToken cancelToken = new CancelToken();                 // 协作式取消信号（Ctrl-C 置位）
        CardPrompter prompter = new CardPrompter(terminal);         // 卡片渲染与读入编号（审批、提问共用）
        Approver approver = new CliApprover(prompter, cancelToken);           // 审批：准不准这么做
        QuestionAsker asker = new CliQuestionAsker(prompter, cancelToken);    // 提问：模型主动澄清
        PlanApprover planApprover = new CliPlanApprover(prompter, cancelToken); // M8：Plan Mode 进入/退出两选项确认框

        // 5) 注册工具：M1 只读工具 + M2 写操作工具（Edit/Write/Bash）与待办工具 + M3 的 AskUserQuestion。
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadTool());
        registry.register(new GrepTool());
        registry.register(new GlobTool());
        registry.register(new EditTool());
        registry.register(new WriteTool());
        registry.register(new BashTool());
        registry.register(new TodoWriteTool());
        registry.register(new AskUserQuestionTool(asker)); // M3 新增：模型主动提问
        registry.register(new EnterPlanModeTool(planApprover)); // M8：模型提议进入 Plan Mode（弹框批准）
        registry.register(new ExitPlanModeTool(planApprover));  // M8：模型计划好了请求批准退出

        // 6) M5 长期记忆装配。记忆目录挂在 M4 的 stateDir 结构下（~/.mcc/projects/<项目>/memory/）：
        //    - AgentMemory 读人写的项目约定 <项目>/.mcc/AGENT.md；
        //    - AutoMemory 管模型维护的记忆目录（保证目录存在、读 MEMORY.md 索引）。
        Path stateDir = resolveStateDir(config.stateDir());
        SessionPaths paths = new SessionPaths(stateDir);
        Path memoryDir = paths.memoryDir(workingDir);
        AgentMemory agentMemory = new AgentMemory(workingDir, stateDir);
        AutoMemory autoMemory = new AutoMemory(memoryDir);
        autoMemory.ensureMemoryDirExists();  // 启动时保证目录存在，模型随后可直接 Write（无需 mkdir）

        // 7) 模型边界、渲染器、审批钩子。放在压缩模块之前构造——自动压缩要调模型、压缩过程要渲染。
        //    M3：钩子链挂上审批钩子（只注入 Approver，状态经 ToolContext 下发）；
        //    M5：审批钩子会对「落在记忆目录内的写入」自动放行，故记忆写入不弹卡片。
        ModelClient modelClient = new ModelClient(config);
        // 钩子链顺序 = 判断顺序：M8 的 PlanModeHook 排在 M3 的 ApprovalHook 之前——
        //   计划态下写操作先被 PlanModeHook 拦掉（链短路、走不到审批）；非计划态下 PlanModeHook 一律放行，
        //   交给 ApprovalHook 按老规矩判断。PlanModeHook 管「现在能不能写」、ApprovalHook 管「这次写准不准」。
        HookChain hookChain = new HookChain(List.of(
                new PlanModeHook(),          // M8：计划态拦写操作（只读放行）
                new ApprovalHook(approver))); // M3：写操作先审批
        Renderer renderer = new Renderer();

        // 8) M6：装配分层压缩模块。第①层落盘器挂在工具结果入库点；②–⑤ 四层挂在组装阶段里投影。
        //    结果预算阈值：以配置 toolResultBudget 为底，再为未列出的工具补上各自声明的 maxResultChars()。
        Map<String, Integer> budgetByTool = new HashMap<>(config.toolResultBudget());
        for (var tool : registry.all()) {
            budgetByTool.putIfAbsent(tool.name(), tool.maxResultChars());
        }
        ToolResultCompactor toolResultCompactor = new ToolResultCompactor(
                paths.toolResultsDir(workingDir), budgetByTool, MccConfig.DEFAULT_TOOL_RESULT_CHARS);
        CompactionManager compactionManager = new CompactionManager(
                toolResultCompactor,                    // ① 工具结果预算（入库时落盘）
                new SnipCompactor(),                    // ② Snip（删空结果 / 被拒调用）
                new MicroCompactor(config),             // ③ 微压缩（空闲后清理可再取旧结果）
                new ContextCollapser(config),           // ④ 上下文折叠（结构化摘要，不调模型）
                new AutoCompactor(modelClient, renderer),// ⑤ 自动压缩（调模型）+ 紧急压缩兜底
                config, renderer);

        // 8.5) M7 Skills 装配：扫描「全局 ~/.mcc/skills」与「项目级 <项目>/.mcc/skills」两层，解析每个
        //      skill-name/SKILL.md 的 frontmatter，得到技能清单（项目级覆盖全局同名）。加载器会读取文件，
        //      但清单仅保留元信息；正文由模型按需 Read 才进入上下文（渐进式披露）。关闭时完全不扫描。
        MccConfig.SkillSettings skillSettings = config.skills();
        List<Skill> skills;
        if (skillSettings.enabled()) {
            Path globalSkillsDir = Path.of(System.getProperty("user.home"), ".mcc", "skills");
            Path projectSkillsDir = workingDir.resolve(".mcc").resolve("skills");
            SkillLoader skillLoader = new SkillLoader(globalSkillsDir, projectSkillsDir);
            skillLoader.ensureGlobalDirExists();  // 全局目录不存在就建个空的，方便用户往里放技能
            skills = skillLoader.loadAll();
        } else {
            skills = List.of();
        }

        // 9) 组装上下文管线：系统提示词 → 记忆（M5）→ Skills 清单（M7）→ 计划态提示（M8）→ 压缩后的会话历史
        //    （M6 用 CompactionStage 替换 HistoryStage）。SkillStage 和 MemoryStage 同类——稳定的靠前背景知识。
        MemoryStage memoryStage = new MemoryStage(agentMemory, autoMemory); // M5：AGENT.md + 记忆说明 + MEMORY.md 索引
        SkillStage skillStage = new SkillStage(skills, skillSettings.listingMaxChars()); // M7：行为说明 + 技能清单
        ContextAssembler assembler = new ContextAssembler(List.of(
                new SystemPromptStage(SystemPrompt.TEXT),
                memoryStage,
                skillStage,                              // M7：插在记忆之后、压缩历史之前
                new PlanModeStage(),                     // M8：计划态开启时注入「只调研不修改」的提示
                new CompactionStage(compactionManager)  // M6：接管「会话历史」这一段，内部跑 ②–⑤ 投影链
        ));

        // M5：轮末补充提取用的「记忆专用」工具集——只读工具 + Edit/Write（写入由 memoryWritesOnly 约束到记忆目录）。
        ToolRegistry memoryRegistry = new ToolRegistry();
        memoryRegistry.register(new ReadTool());
        memoryRegistry.register(new GrepTool());
        memoryRegistry.register(new GlobTool());
        memoryRegistry.register(new EditTool());
        memoryRegistry.register(new WriteTool());
        MemoryExtractor memoryExtractor =
                new MemoryExtractor(modelClient, memoryRegistry, hookChain, autoMemory, config, renderer);

        // M6：控制循环带上 compactionManager——工具结果入库前先过第①层落盘。
        AgentLoop agentLoop = new AgentLoop(modelClient, assembler, registry, hookChain, renderer,
                config, cancelToken, memoryDir, memoryExtractor, compactionManager);
        Session session = new Session(workingDir);

        // 10) M4：会话持久化。生成本次会话的 sessionId 与日志/快照文件，作为 sink 注入 Session——
        //     此后每次 append*/更新待办都会「顺手」落盘（会话默认一律落盘）。
        String sessionId = UUID.randomUUID().toString();
        SessionStore store = new SessionStore(paths.sessionLog(workingDir, sessionId));
        TodoSnapshotStore todoStore = new TodoSnapshotStore(paths.todoSnapshot(workingDir, sessionId));
        session.attachPersistence(sessionId, store, todoStore);

        // 11) 启动交互式会话。Repl 负责在每次任务执行期间把 Ctrl-C 接成"取消当前任务"，
        //     并提供 /resume、/clear，以及 M5 的 /memory（查看/编辑记忆文件）。
        Repl repl = new Repl(agentLoop, session, registry, renderer, terminal, cancelToken,
                paths, prompter, stateDir, autoMemory, VERSION, config.model(),
                config, compactionManager, memoryStage,   // M6：/context 占用分布、/compact 手动压缩
                skills, skillSettings.enabled());          // M7：/skills 列出当前可用技能
        try {
            repl.start();
        } finally {
            try {
                terminal.close();
            } catch (IOException ignore) {
                // 退出时关闭失败无妨。
            }
        }
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
