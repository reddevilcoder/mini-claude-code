package com.minicc.cli;

import com.minicc.agent.AgentLoop;
import com.minicc.agent.CancelToken;
import com.minicc.cli.command.ClearCommand;
import com.minicc.cli.command.CompactCommand;
import com.minicc.cli.command.ContextCommand;
import com.minicc.cli.command.ExitCommand;
import com.minicc.cli.command.HelpCommand;
import com.minicc.cli.command.MemoryCommand;
import com.minicc.cli.command.PlanCommand;
import com.minicc.cli.command.ResumeCommand;
import com.minicc.cli.command.SkillsCommand;
import com.minicc.cli.command.SlashCommand;
import com.minicc.cli.command.ToolsCommand;
import com.minicc.config.MccConfig;
import com.minicc.compact.CompactionManager;
import com.minicc.context.MemoryStage;
import com.minicc.memory.AutoMemory;
import com.minicc.persistence.SessionPaths;
import com.minicc.session.Session;
import com.minicc.skill.Skill;
import com.minicc.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交互式会话主循环（REPL：Read-Eval-Print Loop，读取—求值—打印 循环）。
 *
 * <p>一轮处理一条用户输入：读取输入（Read）→ 若以 / 开头就执行斜杠命令、否则交给 AgentLoop 跑一轮（Eval）
 * → 结果打印到终端（Print）→ 回到开头等待下一次输入（Loop）。
 *
 * <p>注意两层循环：Repl 是外层（人与程序的交互），AgentLoop 是内层
 * （在一次用户输入内部驱动"决策—行动—观察"的模型与工具循环）。
 *
 * <p><b>面试关注：为什么斜杠命令不交给模型？</b>
 * /clear、/resume 等是确定的本地操作，通过命令表直接分发，避免额外模型延迟与不确定性。
 * commands 是命令模式的注册表；增加本地命令只需实现 SlashCommand 并注册，
 * 这些命令也不是向模型声明的 Function Calling 工具。
 *
 * <p><b>面试追问：为什么此处可以用一个可变 Session？</b>
 * start 在当前任务结束前不会读取下一条自然语言输入，因此多个用户任务不会同时修改历史。
 * 工具层仍可能并行读取共享状态，不能据此外推所有组件都无需考虑线程安全。
 * 单任务设计也意味着模型请求和轮末记忆提取的等待会延迟下一次输入。
 */
public class Repl {

    private final AgentLoop agentLoop;
    private final Session session;
    private final ToolRegistry registry;
    private final Renderer renderer;
    private final Terminal terminal;        // M3：由上层创建、全程共用（回合间输入与卡片都用它）
    private final CancelToken cancelToken;   // M3：协作式取消信号
    private final SessionPaths paths;        // M4：会话文件路径推导（/clear 换文件、/resume 找文件）
    private final CardPrompter prompter;     // M5：/memory 的编号选择卡片（与审批/提问共用一套）
    private final Path stateDir;             // M5：~/.mcc，定位全局 AGENT.md
    private final AutoMemory autoMemory;     // M5：记忆目录门面（/memory 列出 MEMORY.md 与主题文件）
    private final String version;
    private final String model;
    private final MccConfig config;                    // M6：/context 需要 contextLimit 作分母
    private final CompactionManager compactionManager; // M6：/compact 手动压缩
    private final MemoryStage memoryStage;             // M6：/context 估算记忆段占用
    private final List<Skill> skills;                  // M7：/skills 列出当前可用技能
    private final boolean skillsEnabled;               // M7：Skills 是否启用（关闭时 /skills 作提示）

    // 斜杠命令表，保留注册顺序供 /help 展示。
    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();

    public Repl(AgentLoop agentLoop, Session session, ToolRegistry registry,
                Renderer renderer, Terminal terminal, CancelToken cancelToken,
                SessionPaths paths, CardPrompter prompter, Path stateDir, AutoMemory autoMemory,
                String version, String model,
                MccConfig config, CompactionManager compactionManager, MemoryStage memoryStage,
                List<Skill> skills, boolean skillsEnabled) {
        this.agentLoop = agentLoop;
        this.session = session;
        this.registry = registry;
        this.renderer = renderer;
        this.terminal = terminal;
        this.cancelToken = cancelToken;
        this.paths = paths;
        this.prompter = prompter;
        this.stateDir = stateDir;
        this.autoMemory = autoMemory;
        this.version = version;
        this.model = model;
        this.config = config;
        this.compactionManager = compactionManager;
        this.memoryStage = memoryStage;
        this.skills = skills;
        this.skillsEnabled = skillsEnabled;
        registerCommands();
    }

    /** 启动会话循环，直到用户退出或输入结束。 */
    public void start() {
        renderer.banner(version, model, session.workingDir().toString());
        // 用 JLine 接管行输入：由它负责退格、方向键、历史记录以及中文等多字节字符的编辑，
        // 而不是依赖终端 cooked 模式（后者对多字节字符的退格支持很差）。终端由上层创建并共用。
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        while (true) {
            String line;
            try {
                line = reader.readLine("> "); // JLine 自行打印提示符并处理行编辑
            } catch (UserInterruptException | EndOfFileException e) { // Ctrl-C / Ctrl-D：结束会话
                break;
            }
            if (line == null) {
                break;
            }
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("/")) {
                boolean exit = dispatchSlash(line);
                if (exit) {
                    break;
                }
            } else {
                // 自然语言输入：作为一条用户消息，驱动一轮 Agent 循环。
                session.appendUserMessage(line);
                runTask();
            }
        }
    }

    /**
     * 跑一轮 Agent 循环，并在这期间把 Ctrl-C 接成"取消当前任务"。
     *
     * <p>Ctrl-C 在终端里是 SIGINT 信号，不是输入流里的字符，所以用 JLine 的
     * {@code terminal.handle(Signal.INT, ...)} 装一个处理函数：按下就置位 {@link CancelToken}，
     * 循环在安全检查点停下（见 AgentLoop / ToolScheduler / CommandRunner）。任务结束后还原原处理函数，
     * 把 Ctrl-C 交回外层 {@code readLine("> ")}：在 {@code >} 等待下一条消息时，Ctrl-C 与 Ctrl-D
     * 都是结束会话、退出程序（见 {@link #start()}），不再是取消任务。
     *
     * <p>卡片在等编号时，JLine 的 readLine 会临时接管 Ctrl-C（抛 UserInterruptException），
     * CardPrompter 据此返回"取消"；读完 readLine 再还原到这里装的处理函数。两种情形都落到"取消任务"。
     */
    private void runTask() {
        // 必须先清掉上一轮取消信号，再安装本轮处理函数；finally 保证异常退出也会归还终端信号处理权。
        cancelToken.reset();
        Terminal.SignalHandler previous = terminal.handle(Terminal.Signal.INT, s -> cancelToken.cancel());
        try {
            agentLoop.run(session);
        } finally {
            terminal.handle(Terminal.Signal.INT, previous); // 还原：回到 > 后，Ctrl-C 交给外层 readLine，结束会话
        }
    }

    /** 分发斜杠命令；返回 true 表示要退出。 */
    private boolean dispatchSlash(String line) {
        String body = line.substring(1); // 去掉前导 /
        int space = body.indexOf(' ');
        String name = space < 0 ? body : body.substring(0, space);
        String args = space < 0 ? "" : body.substring(space + 1).strip();

        SlashCommand command = commands.get(name);
        if (command == null) {
            renderer.info("未知命令：/" + name + "，输入 /help 查看可用命令。");
            return false;
        }
        return command.run(args);
    }

    /** 注册斜杠命令，顺序即 {@code /help} 的展示顺序。 */
    private void registerCommands() {
        add(new HelpCommand(commands.values(), renderer));
        add(new ToolsCommand(registry, renderer));
        add(new SkillsCommand(skills, skillsEnabled, renderer));                     // M7：列出技能
        add(new ContextCommand(session, renderer, config, registry, memoryStage));  // M6：占用分布
        add(new ClearCommand(session, paths, renderer));
        add(new ResumeCommand(session, paths, renderer));
        add(new ExitCommand(renderer));
        add(new MemoryCommand(session.workingDir(), stateDir, autoMemory, prompter, renderer)); // M5
        add(new CompactCommand(renderer, session, compactionManager));              // M6：手动压缩
        add(new PlanCommand(session, renderer));                                    // M8：进入 Plan Mode
    }

    private void add(SlashCommand command) {
        commands.put(command.name(), command);
    }
}
