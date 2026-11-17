package com.minicc.permission;

import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import com.minicc.tool.hook.PreExecuteHook;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.nio.file.Path;

/**
 * 审批钩子（M3）：在工具执行前判断是否放行有副作用的操作。
 *
 * <p>只读工具直接放行；写操作先查会话级放行，未命中则弹审批卡片等用户决定。
 * 挂在 M1 预留、M2 为空的 {@code HookChain} 上——M2 的写操作工具（Edit/Write/Bash）
 * 本身一行都不用改，执行之前自动多过这道审批关。
 *
 * <p>权限三态（allow/deny/ask）：mini-cc 把"是否要问（ask）"内聚在钩子里当场弹卡片消化，循环只看到放行/拒绝。
 *
 * <p>权限由运行时强制，不依赖模型自觉：模型只管提出"我要调用 Edit 改这个文件"，准不准由这里拦下来决定。
 *
 * <p><b>面试关注：提示词约束和运行时约束差在哪里？</b>提示词只能影响模型选择，本钩子在真正执行前
 * 做确定性判断。拒绝原因还会作为工具观察回填，模型才有信息调整方案。审批通过不代表工具必定成功：
 * 参数校验、文件竞态、命令护栏与沙箱仍可能使执行失败，也不会因为允许一次就自动放行后续调用。
 *
 * <p><b>面试追问：记忆提取“仅限写记忆”是否完全隔离？</b>这里先放行只读类工具，再放行记忆目录内的
 * Edit/Write，最后拒绝其余写操作，且会话放行不能越过该拒绝。路径只用 normalize + startsWith 做词法归属判断，
 * 没有解析符号链接；因此这是简化策略，不能宣称已防止软链接指向目录之外的写入。只读类工具的范围同样来自
 * 工具实现者的标记，注册新工具时必须审查该声明。
 */
public class ApprovalHook implements PreExecuteHook {

    // 只依赖 Approver（弹卡片、等用户选，CLI 实现）这个"真·单例"。
    // 会话级放行状态 PermissionState 不作为字段注入——它随会话走，由 ToolContext 带进来，
    // 和 M2 的 WorkspaceState 一样随上下文传递；/clear 会替换权限对象，钩子无须自行清理会话授权。
    private final Approver approver;

    public ApprovalHook(Approver approver) {
        this.approver = approver;
    }

    @Override
    public Decision beforeExecute(AssistantMessage.ToolCall call, Tool tool, ToolContext ctx) {
        PermissionState permissions = ctx.permissions(); // 从上下文取本会话的放行状态

        // 1) 只读类工具不走文件/命令审批；TodoWrite 等仍可更新内部状态，不能理解为绝无副作用。
        if (tool.isReadOnly()) {
            return Decision.allow();
        }

        // 2) M5 记忆写入：写操作的目标落在记忆目录内 → 这是 Agent 自己的笔记、不碰用户代码，
        //    直接放行、不弹审批卡片（就像 TodoWrite 一样）。
        if (isWriteTool(tool) && isUnderMemoryDir(call, ctx)) {
            return Decision.allow();
        }

        // 3) M5 提取回合（memoryWritesOnly）：此时只允许写记忆目录——目标既然没命中上一条，
        //    说明它在记忆目录之外，提取不该碰用户代码，直接拒绝（不弹卡片，提取无人值守）。
        if (ctx.memoryWritesOnly()) {
            return Decision.deny("记忆提取回合只能写入记忆目录，不能修改："
                    + ToolCallHelper.getString(call, "file_path", "(该操作)"));
        }

        // 4) 本会话已放行同类操作 → 直接放行。
        //    - Edit/Write：会话已进入"自动接受编辑"
        //    - Bash：该命令前缀已在本会话放行过
        if (permissions.isGranted(call, tool)) {
            return Decision.allow();
        }

        // 5) 以上都不命中 → 弹审批卡片，暂停等用户选。
        Approval choice = approver.requestApproval(call, tool, ctx);
        switch (choice.kind()) {
            case ALLOW_ONCE:
                return Decision.allow();
            case ALLOW_SESSION:
                permissions.grantSession(call, tool);  // 记入本会话的放行状态
                return Decision.allow();
            case REJECT:
            default:
                return Decision.deny(choice.reason()); // 带回用户填写的原因
        }
    }

    /** 是否文件写入工具（Edit/Write）。记忆写入只走这两个工具（Bash 等不参与记忆放行判断）。 */
    private static boolean isWriteTool(Tool tool) {
        String name = tool.name();
        return "Edit".equals(name) || "Write".equals(name);
    }

    /**
     * 判断这次写操作的目标文件是否落在记忆目录之内。
     *
     * <p>从 {@code call} 里取 {@code file_path}（用 M1 的 {@link ToolCallHelper}），规范化后
     * 判断是否在 {@code ctx.memoryDir()} 之下。记忆目录为空（未启用记忆）或路径非绝对路径时返回 false。
     */
    private static boolean isUnderMemoryDir(AssistantMessage.ToolCall call, ToolContext ctx) {
        Path memoryDir = ctx.memoryDir();
        if (memoryDir == null) {
            return false;
        }
        String filePath = ToolCallHelper.getString(call, "file_path", "").trim();
        if (filePath.isEmpty()) {
            return false;
        }
        Path target = Path.of(filePath);
        if (!target.isAbsolute()) {
            return false;   // 相对路径无法可靠判断归属，交给工具自己报错
        }
        Path normalizedTarget = target.normalize();
        // normalize 只消解 . 和 ..；startsWith 按路径段比较，避免 memory-other 的字符串前缀误判，
        // 但二者都不跟随符号链接，也不锁定校验后到写入前的文件系统状态（TOCTOU）。
        Path normalizedMemory = memoryDir.toAbsolutePath().normalize();
        return normalizedTarget.startsWith(normalizedMemory);
    }
}
