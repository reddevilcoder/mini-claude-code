package com.minicc.permission;

import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级权限状态（M3）：记录本会话内用户主动放行过的范围。只在本会话有效，退出即失效。
 *
 * <p>归属与传递方式与 M2 的 {@code WorkspaceState} 完全一致：一个会话一个实例，由
 * {@code Session} 持有（生命周期 = 会话，{@code /clear} 时随新会话重建），再通过
 * {@code ToolContext} 传给 {@code ApprovalHook}。这样审批钩子保持无状态，既不会在
 * {@code /clear} 后读到上一个会话遗留的 acceptEdits，也和既有模式对齐。
 *
 * <p>这是"会话级放行"的落地，刻意不做持久化的规则文件（那是进阶扩展）。
 *
 * <p>简化：更完整的做法会用 {@code acceptEdits} 等权限模式，并把一条条权限规则
 * （{@code Bash(mvn:*)} 之类）写进权限上下文，命令前缀提取还要走 AST 解析、env 变量剥离、安全校验。
 * mini-cc 把"文件修改"的会话放行简化为一个布尔开关，把"命令"的会话放行简化为<b>命令第一个词</b>的集合。
 *
 * <p>线程安全：只读工具可并行，审批虽在串行写路径上触发，但状态仍可能被并发读到，用 volatile + 并发集合。
 */
public class PermissionState {

    /** Edit/Write：会话是否进入"自动接受编辑"（acceptEdits 模式）。 */
    private volatile boolean acceptEdits = false;

    /** Bash：本会话已放行的命令前缀（如 "mvn"、"git"）。 */
    private final Set<String> allowedBashPrefixes = ConcurrentHashMap.newKeySet();

    /** 该工具调用本会话是否已被放行。 */
    public boolean isGranted(AssistantMessage.ToolCall call, Tool tool) {
        if (isFileEdit(tool)) {              // Edit / Write
            return acceptEdits;
        }
        if (isBash(tool)) {                  // Bash：按命令前缀判断
            return allowedBashPrefixes.contains(bashPrefix(call));
        }
        return false;
    }

    /** 用户选了"本会话内不再询问同类"时记入放行。 */
    public void grantSession(AssistantMessage.ToolCall call, Tool tool) {
        if (isFileEdit(tool)) {
            acceptEdits = true;                        // 整个会话的文件修改都放行
        } else if (isBash(tool)) {
            allowedBashPrefixes.add(bashPrefix(call)); // 该前缀的命令都放行
        }
    }

    /** 会话是否已进入"自动接受编辑"（供卡片/测试查询）。 */
    public boolean acceptEdits() {
        return acceptEdits;
    }

    /** 取命令前缀：简化为命令的第一个词（如 "mvn -q test" → "mvn"）。 */
    public String bashPrefix(AssistantMessage.ToolCall call) {
        String cmd = ToolCallHelper.getString(call, "command", "").trim();
        if (cmd.isEmpty()) {
            return "";
        }
        int sp = cmd.indexOf(' ');
        return sp < 0 ? cmd : cmd.substring(0, sp);
    }

    /** 是否文件修改工具（Edit / Write）。 */
    private static boolean isFileEdit(Tool tool) {
        String name = tool.name();
        return "Edit".equals(name) || "Write".equals(name);
    }

    /** 是否 Bash 工具。 */
    private static boolean isBash(Tool tool) {
        return "Bash".equals(tool.name());
    }
}
