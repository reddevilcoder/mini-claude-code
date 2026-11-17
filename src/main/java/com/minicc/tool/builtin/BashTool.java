package com.minicc.tool.builtin;

import com.minicc.tool.Schemas;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.ToolResult;
import com.minicc.tool.bash.CommandRunner;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bash 工具：在 shell 里执行命令（编译、测试、git 等）。
 *
 * <p>M2 实现"同步执行 + 超时 + 截断 + 危险命令护栏 + 可选沙箱"，
 * 把真正的执行委托给 {@link CommandRunner}。不做后台执行、流式输出、细粒度命令权限。
 *
 * <p>工作目录在命令间保持（= 会话项目目录），但 shell 变量等状态不跨命令保持。
 *
 * <p><b>面试关注：为什么 Bash 一律串行且需要审批？</b>仅凭命令文本很难可靠判断副作用，
 * 即使看起来是查询，也可能调用脚本、插件或重定向。因此本工具保守地把整个能力标为有副作用，
 * 不按命令内容临时改成只读；计划态也会整体禁用它。每次执行创建新的 shell 子进程，
 * workingDir 总是会话项目目录，上一条命令中的 cd、export 都不会成为下一条命令的持久状态。
 */
public class BashTool implements Tool {

    /** 超时上限（毫秒）：10 分钟。 */
    private static final long MAX_TIMEOUT_MS = 600_000;

    private final CommandRunner runner = new CommandRunner();

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        return """
                在 shell 中执行一条命令并返回输出。
                用法：
                - 工作目录在命令间保持，但 shell 变量等状态不保持。
                - 优先使用专用工具而非 shell 命令：读文件用 Read（不要用 cat/head/tail）、
                  改文件用 Edit（不要用 sed/awk）、新建文件用 Write（不要用 echo >）、
                  找文件用 Glob、搜内容用 Grep（不要用 find/grep）。
                - 多条命令若相互依赖请用 && 串联，不要用换行分隔。
                - 可用 timeout 指定超时（毫秒），默认 2 分钟，上限 10 分钟。""";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("command", Schemas.prop("string", "要执行的命令"));
        props.put("timeout", Schemas.prop("integer", "超时（毫秒），可选，默认 2 分钟，上限 10 分钟"));
        props.put("description", Schemas.prop("string", "一句话说明这条命令做什么，可选"));
        return Schemas.object(props, List.of("command"));
    }

    @Override
    public boolean isReadOnly() {
        return false; // 能改环境，需审批（M3）、需沙箱
    }

    @Override
    public boolean isConcurrencySafe() {
        return false; // 有副作用，串行执行
    }

    @Override
    public ToolResult execute(AssistantMessage.ToolCall call, ToolContext ctx) {
        String command = ToolCallHelper.getString(call, "command", "").trim();
        if (command.isEmpty()) {
            return ToolResult.error("缺少必填参数 command。");
        }
        // 超时：参数优先，否则取配置默认；并夹在 (0, 上限] 内。
        long timeout = ToolCallHelper.getInt(call, "timeout", (int) Math.min(ctx.bashTimeoutMs(), Integer.MAX_VALUE)).longValue();
        if (timeout <= 0) {
            timeout = ctx.bashTimeoutMs();
        }
        timeout = Math.min(timeout, MAX_TIMEOUT_MS);

        return runner.run(command, ctx.workingDir(), timeout, ctx.sandbox(), ctx.cancelToken());
    }
}
