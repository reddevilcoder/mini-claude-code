package com.minicc.cli;

import com.minicc.agent.CancelToken;
import com.minicc.permission.Approval;
import com.minicc.permission.Approver;
import com.minicc.tool.Tool;
import com.minicc.tool.ToolCallHelper;
import com.minicc.tool.ToolContext;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 审批交互的 CLI 实现（M3）：把 {@link Approver} 的抽象落到终端卡片上。
 *
 * <p>职责：根据工具类型组装审批卡片的标题、预览与选项，调用 {@link CardPrompter} 打印出来并读一行编号，
 * 再把用户的选择翻译回 {@link Approval}。（模型主动提问是另一件事，见 {@link CliQuestionAsker}；两者
 * 只共用 {@link CardPrompter}。）
 *
 * <p>卡片在等编号时，按 Ctrl-C 会让 {@link CardPrompter} 返回"取消"。本类据此置位
 * {@link CancelToken}，让控制循环在安全检查点停下。取消不等于任何一个选项——只想拒绝这一次、
 * 让 Agent 换个做法，要输入编号 3 并填写原因。
 */
public class CliApprover implements Approver {

    private final CardPrompter prompter;
    private final CancelToken cancelToken;

    public CliApprover(CardPrompter prompter, CancelToken cancelToken) {
        this.prompter = prompter;
        this.cancelToken = cancelToken;
    }

    @Override
    public Approval requestApproval(AssistantMessage.ToolCall call, Tool tool, ToolContext ctx) {
        List<String> options = List.of("允许", sessionOptionLabel(tool, call, ctx), "拒绝，并说明原因");
        int idx = prompter.select(buildPreview(call, tool), buildTitle(call, tool), options);

        if (idx < 0) {                 // Ctrl-C / Ctrl-D：取消整个任务
            cancelToken.cancel();
            return Approval.reject("用户取消了本次任务。");
        }
        if (idx == 0) {
            return Approval.allowOnce();
        }
        if (idx == 1) {
            return Approval.allowSession();
        }
        // idx == 2：拒绝并说明原因。
        String reason = prompter.readLine("  拒绝原因（告诉 Agent 换个做法，直接回车可跳过）> ");
        if (reason == null) {          // Ctrl-C/Ctrl-D：也按取消处理
            cancelToken.cancel();
            return Approval.reject("用户取消了本次任务。");
        }
        reason = reason.strip();
        return Approval.reject(reason.isEmpty() ? "用户拒绝了这次操作，未说明具体原因。" : reason);
    }

    /** "本会话内放行同类"选项的文案，按工具类型把放行范围写清楚。 */
    private static String sessionOptionLabel(Tool tool, AssistantMessage.ToolCall call, ToolContext ctx) {
        String name = tool.name();
        if ("Bash".equals(name)) {
            String prefix = ctx.permissions().bashPrefix(call);
            return "允许，本会话内的所有 " + prefix + " 命令不再询问";
        }
        // Edit / Write 及其它写操作。
        return "允许，本会话内的所有文件修改不再询问";
    }

    /** 卡片标题：问"准不准这么做"。 */
    private static String buildTitle(AssistantMessage.ToolCall call, Tool tool) {
        String name = tool.name();
        switch (name) {
            case "Edit":
                return "对 " + fileName(ToolCallHelper.getString(call, "file_path", "")) + " 应用此修改？";
            case "Write":
                return "写入 " + fileName(ToolCallHelper.getString(call, "file_path", "")) + "？";
            case "Bash":
                return "执行命令？";
            default:
                return "执行 " + name + "？";
        }
    }

    /** 卡片预览：改文件给出 diff 片段，Bash 给出将执行的命令。 */
    private static List<String> buildPreview(AssistantMessage.ToolCall call, Tool tool) {
        String name = tool.name();
        switch (name) {
            case "Edit": {
                List<String> lines = new ArrayList<>();
                lines.add("变更预览：" + ToolCallHelper.getString(call, "file_path", ""));
                for (String l : splitFew(ToolCallHelper.getString(call, "old_string", ""), 3)) {
                    lines.add("- " + l);
                }
                for (String l : splitFew(ToolCallHelper.getString(call, "new_string", ""), 3)) {
                    lines.add("+ " + l);
                }
                return lines;
            }
            case "Write": {
                List<String> lines = new ArrayList<>();
                lines.add("写入预览：" + ToolCallHelper.getString(call, "file_path", ""));
                for (String l : splitFew(ToolCallHelper.getString(call, "content", ""), 4)) {
                    lines.add("  " + l);
                }
                return lines;
            }
            case "Bash": {
                List<String> lines = new ArrayList<>();
                lines.add("$ " + ToolCallHelper.getString(call, "command", ""));
                String desc = ToolCallHelper.getString(call, "description", "");
                if (!desc.isBlank()) {
                    lines.add("# " + desc);
                }
                return lines;
            }
            default:
                return List.of();
        }
    }

    /** 取路径最后一段作为显示名，路径为空时原样返回。 */
    private static String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "(未指定文件)";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** 取文本的前 n 行（用于预览，避免刷屏）。 */
    private static List<String> splitFew(String text, int n) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String[] all = text.split("\n", -1);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(all.length, n); i++) {
            out.add(all[i]);
        }
        if (all.length > n) {
            out.add("…（共 " + all.length + " 行）");
        }
        return out;
    }
}
