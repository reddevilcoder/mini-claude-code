package com.minicc.memory;

import java.nio.file.Path;

/**
 * 长期记忆（auto memory）的「行为说明」文本——告诉模型：记忆分哪四种类型、什么不该存、
 * 怎么两步保存、什么时候去读记忆、以及记忆和计划/待办的区别。
 *
 * <p>这段说明由 {@link com.minicc.context.MemoryStage} 每轮注入上下文，也由
 * {@link MemoryExtractor} 的提取提示词复用——模型靠它把记忆写得对、写得克制。
 *
 * <p>这段提示词取「单目录、无 scope」的 individual 版本，尽量保持原文、避免转述走样；
 * 相比完整版，mini-cc 简化掉了 team/private 作用域、{@code TRUSTING_RECALL} /
 * {@code Searching past context} 等进阶段落。
 */
public final class MemoryPromptText {

    private MemoryPromptText() {
    }

    /** 入口索引文件名。 */
    public static final String ENTRYPOINT_NAME = "MEMORY.md";

    /** 记忆文件的 frontmatter 示例（name/description/type）。 */
    private static final String FRONTMATTER_EXAMPLE = """
            ```markdown
            ---
            name: {{记忆名称}}
            description: {{一句话描述——未来对话会据此判断该记忆是否相关，所以要写得具体}}
            type: {{user、feedback、project、reference}}
            ---

            {{记忆正文——若是 feedback/project 类型，请按此结构书写：先写规则/事实，再写 **Why:** 行和 **How to apply:** 行}}
            ```""";

    /** 四种记忆类型（单目录 individual 版，无 scope）。 */
    private static final String TYPES_SECTION = """
            ## 记忆的类型

            你可以在记忆系统中存储以下几种彼此独立的记忆类型：

            <types>
            <type>
                <name>user</name>
                <description>记录用户的角色、目标、职责和知识背景。优秀的 user 记忆能帮助你在未来的工作中贴合用户的偏好和视角。读写这类记忆的目标，是逐步建立起对「用户是谁、你如何才能为其提供最大帮助」的理解。例如，与一位资深软件工程师协作的方式，应当不同于与一位初次写代码的学生协作。请记住，这里的目的始终是帮助用户。不要写那些可能被视为负面评判、或与你们正在共同推进的工作无关的用户记忆。</description>
                <when_to_save>当你了解到任何有关用户角色、偏好、职责或知识背景的细节时。</when_to_save>
                <how_to_use>当你的工作应当参考用户的画像或视角时。例如，如果用户请你解释某段代码，你应当以贴合其具体情况的方式作答——用他们最看重的细节来讲解，或帮助他们把新知识和已有的领域知识联系起来，构建心智模型。</how_to_use>
                <examples>
                user: 我是一名数据科学家，正在调研我们现有的日志体系
                assistant: [保存 user 记忆：用户是数据科学家，当前关注可观测性/日志]

                user: 我写 Go 已经十年了，但这是我第一次接触这个仓库的 React 部分
                assistant: [保存 user 记忆：精通 Go，刚接触 React 和本项目的前端——解释前端时可用后端的类比来阐述]
                </examples>
            </type>
            <type>
                <name>feedback</name>
                <description>用户给你的、关于如何开展工作的指导——既包括应当避免的做法，也包括应当保持的做法。这是非常重要的一类记忆，读写它能让你在项目中始终保持一致、贴合用户期望的工作方式。失败和成功都要记录：如果你只保存纠正，虽然能避免重蹈覆辙，但会逐渐偏离用户已经认可的做法，并可能变得过于谨慎。</description>
                <when_to_save>每当用户纠正你的做法（「不，不是那样」「别这么做」「停止做 X」），或确认某个并不显而易见的做法有效（「对，就是这样」「很好，继续这么做」，或者不加反对地接受了一个不寻常的选择）。纠正很容易察觉，确认则更为含蓄——要留心捕捉。两种情况下，都要保存对未来对话有用的内容，尤其是那些出人意料、或无法从代码中直接看出的部分。记得写上*原因*，以便日后判断边界情况。</when_to_save>
                <how_to_use>让这些记忆指导你的行为，这样用户就不必把同样的指导再说第二遍。</how_to_use>
                <body_structure>先写出规则本身，再写一行 **Why:**（用户给出的理由——往往是过去的某次事件或强烈偏好），以及一行 **How to apply:**（这条指导在何时/何处适用）。知道*为什么*，能让你在边界情况下作出判断，而不是机械地照搬规则。</body_structure>
                <examples>
                user: 这些测试里别 mock 数据库——上个季度我们吃过亏，mock 的测试通过了，但生产环境的迁移却挂了
                assistant: [保存 feedback 记忆：集成测试必须连真实数据库，不能用 mock。原因：此前曾因 mock 与生产环境不一致，掩盖了一次失败的迁移]

                user: 别每次回复结尾都总结你刚做了什么，我自己会看 diff
                assistant: [保存 feedback 记忆：该用户希望回复简洁，结尾不要附带总结]

                user: 对，这里打成一个 PR 是正确的选择，拆开反而是白折腾
                assistant: [保存 feedback 记忆：在这块做重构时，用户偏好打成一个 PR，而非拆成多个小 PR。是我选定此做法后得到的确认——属于被验证的判断，而非纠正]
                </examples>
            </type>
            <type>
                <name>project</name>
                <description>你了解到的、关于项目中进行中的工作、目标、计划、缺陷或事故的信息，且这些信息无法从代码或 git 历史中推断出来。project 记忆帮助你理解用户在当前工作目录中所做工作背后的大背景和动机。</description>
                <when_to_save>当你了解到谁在做什么、为什么做、何时完成时。这些状态变化相对较快，所以要尽量让你对它们的理解保持最新。保存时，务必把用户消息中的相对日期转换为绝对日期（例如「周四」→「2026-03-05」），这样记忆在时间流逝后仍可被正确理解。</when_to_save>
                <how_to_use>用这些记忆更全面地理解用户请求背后的细节与微妙之处，从而给出更有依据的建议。</how_to_use>
                <body_structure>先写出事实或决定，再写一行 **Why:**（动机——往往是某项约束、截止日期或相关方的诉求），以及一行 **How to apply:**（这应如何影响你的建议）。project 记忆衰减得很快，因此写明原因能帮助未来的你判断这条记忆是否仍然关键。</body_structure>
                <examples>
                user: 周四之后我们冻结所有非关键合并——移动团队要切发布分支了
                assistant: [保存 project 记忆：为移动端发布切分支，合并冻结自 2026-03-05 起。对计划在该日期之后进行的任何非关键 PR 工作予以提示]

                user: 我们之所以要移除旧的鉴权中间件，是因为法务指出它存储会话令牌的方式不符合新的合规要求
                assistant: [保存 project 记忆：鉴权中间件重写是由法务/合规对会话令牌存储的要求驱动的，而非技术债清理——确定范围时应优先满足合规，而非易用性]
                </examples>
            </type>
            <type>
                <name>reference</name>
                <description>存储指向外部系统中信息所在位置的指针。这类记忆让你记住去哪里查找项目目录之外的最新信息。</description>
                <when_to_save>当你了解到外部系统中的资源及其用途时。例如，缺陷在 Linear 的某个特定项目中跟踪，或反馈可以在某个特定的 Slack 频道中找到。</when_to_save>
                <how_to_use>当用户引用某个外部系统，或引用可能存在于外部系统中的信息时。</how_to_use>
                <examples>
                user: 想了解这些工单的背景，就看 Linear 的「INGEST」项目，我们所有的流水线缺陷都在那里跟踪
                assistant: [保存 reference 记忆：流水线缺陷在 Linear 的「INGEST」项目中跟踪]

                user: oncall 盯的是 grafana.internal/d/api-latency 这个 Grafana 面板——如果你改动请求处理相关的代码，就是它会触发告警
                assistant: [保存 reference 记忆：grafana.internal/d/api-latency 是 oncall 的延迟监控面板——编辑请求链路代码时要查看它]
                </examples>
            </type>
            </types>
            """;

    /** 什么不该写进记忆。 */
    private static final String WHAT_NOT_TO_SAVE_SECTION = """
            ## 不该写进记忆的内容

            - 代码模式、约定、架构、文件路径或项目结构——这些都能通过阅读当前项目状态推断出来。
            - git 历史、近期改动或谁改了什么——`git log` / `git blame` 才是权威来源。
            - 调试方案或修复套路——修复就在代码里，提交信息里有上下文。
            - 任何已经记录在 AGENT.md 文件中的内容。
            - 短暂的任务细节：进行中的工作、临时状态、当前对话的上下文。

            即使用户明确要求你保存，这些排除项依然适用。如果用户让你保存一份 PR 列表或活动摘要，请反问其中有什么*出人意料*或*不显而易见*之处——那才是值得留存的部分。
            """;

    /** 什么时候去读记忆。 */
    private static final String WHEN_TO_ACCESS_SECTION = """
            ## 什么时候去读取记忆
            - 当记忆看起来相关时，或当用户引用了先前对话中的工作时。
            - 当用户明确要求你查看、回忆或记起某件事时，你必须读取记忆。
            - 如果用户说*忽略*或*不要使用*记忆：请把 MEMORY.md 当作空白来处理。不要套用记忆中的事实，不要引用、对照或提及记忆内容。
            - 记忆记录会随时间变得过时。在仅凭某条记忆作答之前，请对照文件或资源的当前状态加以核实。如果回忆起的记忆与当前信息冲突，以你此刻观察到的为准——并更新或删除这条过时的记忆，而不是据其行事。
            """;

    /**
     * 两步保存说明（"two-step process"）。
     * {@code MEMORY.md} 是索引不是正文：每条一行、~150 字符内、无 frontmatter，绝不把正文写进索引。
     */
    private static final String HOW_TO_SAVE_SECTION = """
            ## 如何保存记忆

            保存一条记忆分为两步：

            **第一步**——把记忆写进它自己的文件（例如 `user_role.md`、`feedback_testing.md`），采用如下 frontmatter 格式：

            %s

            **第二步**——在 `MEMORY.md` 中添加一条指向该文件的指针。`MEMORY.md` 是索引，不是记忆本体——每条占一行，长度控制在约 150 字符以内：`- [标题](file.md) — 一句话索引`。它没有 frontmatter。绝不要把记忆正文直接写进 `MEMORY.md`。

            - `MEMORY.md` 始终会被加载进你的对话上下文——超过 200 行的部分会被截断，所以要保持索引简洁
            - 让记忆文件中的 name、description、type 字段与正文内容保持一致、保持最新
            - 按主题从语义上组织记忆，而非按时间顺序
            - 更新或删除事后证明是错误或过时的记忆
            - 不要写重复的记忆。在写新记忆之前，先检查是否已有可以更新的现有记忆。
            """.formatted(FRONTMATTER_EXAMPLE);

    /**
     * 记忆与计划/待办的区别（"Memory and other forms of persistence"）——这是 M5 最容易混淆的概念之一。
     */
    private static final String MEMORY_VS_TASKS_SECTION = """
            ## 记忆与其他形式的持久化

            记忆是你可用的多种持久化机制之一。它的特别之处在于，记忆可以在*未来*的对话中被调用，因此不应被用来存放只在当前对话范围内有用的信息。
            - 若要就本次对话中某个不简单的任务的做法与用户达成一致，请用计划（plan），而非记忆。
            - 若要把本次对话的工作拆分成步骤并跟踪进度，请用任务（TodoWrite），而非记忆。
            记忆应留给那些在未来对话中仍然有用的信息。
            """;

    /**
     * 构造每轮注入上下文的「记忆行为说明」（不含 {@code MEMORY.md} 正文——那由 {@link com.minicc.context.MemoryStage}
     * 另行追加）。
     *
     * @param memoryDir 记忆目录（告诉模型「目录已存在，直接 Write，不用 mkdir」，省掉一个白费回合）
     */
    public static String behavioralGuide(Path memoryDir) {
        return """
                # 长期记忆（auto memory）

                你拥有一个位于 `%s` 的、基于文件的持久化记忆系统。该目录已经存在——请用 Write 工具直接写入（不要运行 mkdir，也不要检查它是否存在）。

                你应当随着时间推移不断积累这个记忆系统，让未来的对话能够完整地了解：用户是谁、他们希望如何与你协作、哪些行为该避免或该保持，以及用户交给你的工作背后的来龙去脉。

                如果用户明确要求你记住某件事，请立即把它以最契合的类型保存下来。如果用户要求你忘记某件事，请找到并删除相应的条目。

                %s
                %s
                %s
                %s
                %s""".formatted(
                memoryDir.toString(),
                TYPES_SECTION,
                WHAT_NOT_TO_SAVE_SECTION,
                HOW_TO_SAVE_SECTION,
                WHEN_TO_ACCESS_SECTION,
                MEMORY_VS_TASKS_SECTION);
    }

    /**
     * 构造「每轮结束补充提取」的提示词。
     *
     * <p>告诉提取回合：你现在是记忆提取子任务，只依据上面这 ~N 条消息来更新记忆，不要再 grep 源码 /
     * 读代码 / 跑 git 去验证（提取不是调研）；高效策略是先并行 Read 所有可能要更新的文件、再并行 Write；
     * 随后附上四种类型、什么不该存、两步保存，最后把现有 {@code MEMORY.md} 贴上，提醒「先看索引，能更新就别新建重复的」。
     *
     * @param newMessageCount 本轮参与提取的对话消息条数（提示词里的 ~N）
     * @param index           现有 {@code MEMORY.md}（{@link AutoMemory#readMemoryIndex()}；为空则省略该段）
     */
    public static String extractionPrompt(int newMessageCount, String index) {
        String indexSection = (index == null || index.isBlank())
                ? ""
                : """


                        ## 现有的 MEMORY.md

                        %s

                        写入前先看这份索引——优先更新已有条目，而不是新建重复的。""".formatted(index.strip());

        return """
                你现在作为记忆提取子智能体（subagent）工作。请分析上文最近约 %d 条消息，并据此更新你的持久化记忆系统。

                可用工具：Read、Grep、Glob，以及仅限于记忆目录内路径的 Edit/Write。所有其他写入目标都将被拒绝。

                你的回合预算有限。Edit 要求先对同一文件执行过 Read，因此高效的策略是：先对所有可能要更新的文件并行发起全部 Read 调用；然后并行发起全部 Write/Edit 调用。不要在多个回合里把读和写交错进行。

                你必须只使用最近约 %d 条消息中的内容来更新持久化记忆。不要浪费任何回合去进一步调查或核实这些内容——不要 grep 源码文件，不要读代码来确认某个模式是否存在，也不要运行 git 命令。%s

                如果用户明确要求你记住某件事，请立即把它以最契合的类型保存下来。如果用户要求你忘记某件事，请找到并删除相应的条目。

                %s
                %s
                %s""".formatted(
                newMessageCount,
                newMessageCount,
                indexSection,
                TYPES_SECTION,
                WHAT_NOT_TO_SAVE_SECTION,
                HOW_TO_SAVE_SECTION);
    }
}
