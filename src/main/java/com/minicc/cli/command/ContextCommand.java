package com.minicc.cli.command;

import com.minicc.cli.Renderer;
import com.minicc.config.MccConfig;
import com.minicc.context.MemoryStage;
import com.minicc.context.SystemPrompt;
import com.minicc.compact.TokenEstimator;
import com.minicc.model.UsageTracker;
import com.minicc.session.Session;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolRegistry;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /context}：按组装段落展示当前上下文的 Token 占用分布（M6 升级）。
 *
 * <p>只<b>展示</b>占用、本身<b>不触发任何压缩</b>——所以这里直接估算内存历史的<b>原始占用</b>
 * （即"有多满"，也正是驱动压缩的量），
 * 不跑 ②–⑤ 投影链、不调模型、不改任何状态。
 *
 * <p>按组装管线的三段分别估算：
 * <ul>
 *   <li><b>系统指令与工具描述</b> = 系统提示词 + 各工具定义（名字/描述/参数 Schema）的估算；</li>
 *   <li><b>项目约定与记忆索引</b> = {@link MemoryStage} 注入的那条消息的估算（AGENT.md + 记忆说明 + MEMORY.md）；</li>
 *   <li><b>会话历史（含待办/工具结果）</b> = 内存历史的估算（待办与工具结果本就作为消息留在历史里，一并归此）。</li>
 * </ul>
 *
 * <p>Token 为粗略估算（{@link TokenEstimator}），不接真实分词器——阈值本就留了 buffer，量级准确即可。
 */
public final class ContextCommand implements SlashCommand {

    private final Session session;
    private final Renderer renderer;
    private final MccConfig config;
    private final ToolRegistry registry;
    private final MemoryStage memoryStage;
    private final UsageTracker usage;   // M9：最近一次请求的真实 prompt Token

    public ContextCommand(Session session, Renderer renderer, MccConfig config,
                          ToolRegistry registry, MemoryStage memoryStage, UsageTracker usage) {
        this.usage = usage;
        this.session = session;
        this.renderer = renderer;
        this.config = config;
        this.registry = registry;
        this.memoryStage = memoryStage;
    }

    @Override
    public String name() {
        return "context";
    }

    @Override
    public String description() {
        return "查看当前上下文各部分的 Token 占用分布（估算）";
    }

    @Override
    public boolean run(String args) {
        int system = estimateSystemAndTools();
        int memory = estimateMemory();
        int history = TokenEstimator.ofMessages(session.history());
        int total = system + memory + history;
        int limit = config.contextLimit();

        int percent = limit > 0 ? (int) Math.round(100.0 * total / limit) : 0;
        renderer.info("上下文占用（估算）：" + Renderer.formatTokens(total) + " / "
                + Renderer.formatTokens(limit) + " Token（" + percent + "%）");
        renderer.info("  系统指令与工具描述       " + Renderer.formatTokens(system)
                + "   （系统提示词 + 工具定义 schema）");
        renderer.info("  项目约定与记忆索引       " + Renderer.formatTokens(memory)
                + "   （AGENT.md + 记忆说明 + MEMORY.md）");
        renderer.info("  会话历史（含待办/工具结果） " + Renderer.formatTokens(history));
        // M9：估算值之外，给出服务端报告的真实值（上一次请求的 prompt Token，不含本次之后新增的内容）。
        if (usage != null && usage.lastPromptTokens() >= 0) {
            renderer.info("  上次请求实际输入（服务端） " + Renderer.formatTokens((int) usage.lastPromptTokens()));
        }
        return false;
    }

    /** 估算"系统指令与工具描述"：系统提示词全文 + 每个工具的名字/描述/参数 Schema 文本。 */
    private int estimateSystemAndTools() {
        int sum = TokenEstimator.ofText(SystemPrompt.TEXT);
        for (Tool tool : registry.all()) {
            sum += TokenEstimator.ofText(tool.name());
            sum += TokenEstimator.ofText(tool.description());
            sum += TokenEstimator.ofText(String.valueOf(tool.paramsSchema()));
        }
        return sum;
    }

    /** 估算"项目约定与记忆索引"：让 {@link MemoryStage} 贡献一条消息，再估算它。 */
    private int estimateMemory() {
        if (memoryStage == null) {
            return 0;
        }
        List<Message> tmp = new ArrayList<>();
        memoryStage.contribute(tmp, session);   // 只读：加载 AGENT.md / MEMORY.md，不改会话
        return TokenEstimator.ofMessages(tmp);
    }
}
