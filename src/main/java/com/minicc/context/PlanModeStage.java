package com.minicc.context;

import com.minicc.session.Session;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.List;

/**
 * 组装阶段（M8）：计划态开启时，注入一段「只调研不修改」的提示；非计划态时什么都不加。
 *
 * <p>光靠 {@code PlanModeHook} 拦住写操作还不够——模型得<b>知道</b>自己在计划态、该怎么做
 * （调研、设计方案、用 {@code ExitPlanMode} 请求批准，而不是闷头乱试写操作被反复拒绝）。本阶段仅在计划态
 * 开启时贡献这段提示——mini-cc 把它从「工具结果」挪成一个「组装阶段」，这样 {@code /plan} 进入时也能生效。
 *
 * <p>插在 {@link MemoryStage}/{@link SkillStage} 之后、{@link CompactionStage} 之前——和系统提示词、记忆
 * 一样属于「稳定的靠前内容」：计划态开着的那几轮里它每轮都在、位置固定，利于前缀缓存；放在会话历史之前，
 * 模型读历史、做决策时就已经带着「我在计划态」这个前提了。计划态一关，这段自然就不再注入。这是
 * 「新增阶段不改既有组装」的又一次兑现——不改任何既有阶段，也不改 {@link ContextAssembler}。
 */
public class PlanModeStage implements AssemblyStage {

    // 计划态提示词（彻底探索→理解模式→设计方案→
    // 需要拍板时用 AskUserQuestion 澄清→用 ExitPlanMode 请求批准；在此之前不要写/改任何文件）。
    static final String PLAN_MODE_PROMPT = """
            # 你当前处于 Plan Mode（计划模式）

            这是一个只读的调研与规划阶段。你现在要做的是：
            1. 用 Read / Grep / Glob 彻底调研代码库，理解现有的实现模式与架构；
            2. 考虑多种可行方案及其取舍；需要用户拍板时用 AskUserQuestion 澄清；
            3. 设计一份具体、无歧义的实现计划，直接作为普通消息呈现给用户（不要写进文件）；
            4. 计划准备好后，调用 ExitPlanMode 请求用户批准，确认后再开始执行。

            重要：现在**不要**修改或新建任何文件、也不要执行有副作用的命令——这些操作在计划态下都会被拒绝。
            """;

    /** 仅当本会话处于计划态时，追加一条计划态系统提示；否则不加任何内容。 */
    @Override
    public void contribute(List<Message> messages, Session session) {
        if (session.planMode().isPlanMode()) {
            messages.add(new SystemMessage(PLAN_MODE_PROMPT));
        }
    }
}
