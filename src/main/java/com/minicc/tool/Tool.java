package com.minicc.tool;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.Map;

/**
 * 工具接口：Agent 通过调用工具来观察或操作环境。
 *
 * <p>所有内置工具（M1 的 Read / Grep / Glob，以及后续里程碑的 Edit / Write / Bash 等）都实现这个接口。
 * 为保持教学代码精简，这个接口只保留最关键的几项，
 * 去掉了与 UI 渲染、权限规则、分析埋点相关的方法——权限在 M3 以"执行前钩子"的形式加入，而不是改这个接口。
 */
public interface Tool {

    /** 工具名，模型通过这个名字来调用（如 "Read"）。 */
    String name();

    /** 给模型看的工具说明，讲清楚这个工具做什么、怎么用。 */
    String description();

    /**
     * 工具参数的 JSON Schema（用 Map 表示），供模型按规范生成参数。
     * {@link com.minicc.model.ToolsConverter} 会把它序列化成 JSON 字符串随请求发给模型。
     */
    Map<String, Object> paramsSchema();

    /**
     * 是否只读。只读工具不产生副作用、无需审批（M1 的三个工具都返回 true）。
     * 该标记决定了后续里程碑中要不要走审批钩子。
     */
    boolean isReadOnly();

    /**
     * 是否可并行执行。只读工具通常可并行（M2 用它实现"只读并行、写操作串行"）。
     * M1 不做并行调度，但先把标记声明好。
     */
    boolean isConcurrencySafe();

    /**
     * 执行工具调用，返回结果。结果会作为"观察"回填给模型。
     *
     * @param call 模型发起的工具调用（参数是 JSON 字符串，按字段取值用 {@link ToolCallHelper}）
     * @param ctx  工具执行所需的上下文（工作目录等）
     */
    ToolResult execute(AssistantMessage.ToolCall call, ToolContext ctx);

    /**
     * 该工具单条结果落盘的字符阈值（M6 的"结果预算"元信息）。
     *
     * <p>这是 M1 预埋、M6 读取的约定：工具结果超过这个字符数，就由
     * {@link com.minicc.compact.ToolResultCompactor} 在入库前落盘到 {@code tool-results/}，
     * 历史里只留"预览+路径"。默认给一个有限值
     * （{@code DEFAULT_MAX_RESULT_SIZE_CHARS = 50_000}）；{@code Read} 覆盖为无限（见
     * {@link com.minicc.tool.builtin.ReadTool}）。配置 {@code toolResultBudget} 可按工具名覆盖它。
     *
     * @return 字符阈值；返回 {@code -1} 表示无限大、永不落盘
     */
    default int maxResultChars() {
        return 50_000;
    }
}
