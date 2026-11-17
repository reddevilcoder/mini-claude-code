package com.minicc.persistence;

import com.minicc.session.Session;
import com.minicc.session.TodoItem;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 会话回放：把日志重建成内存 {@link Session}（历史 + 待办）。
 *
 * <p>日志里存的就是 Spring AI {@link Message}，按行顺序放回历史即可，不必再翻译成另一种类型。
 * 助手消息上的工具调用本来就是 {@link AssistantMessage.ToolCall}，原样保留。
 *
 * <p>核心的「点智商」处是中断检测（{@link #dropDanglingToolCalls}）——处理上次会话中途被打断的残局。
 *
 * <p><b>面试关注：恢复的目标是什么？</b>这里恢复模型可继续使用的对话与待办，
 * 不会重新执行历史里的工具。删除悬空调用只是修整消息协议，不代表撤销了已经发生的文件写入或命令。
 * 会话权限和计划态通过 clear 复位，避免把历史消息误当成当前有效的权限授予。
 * 待办从独立快照读取，和日志没有事务关联，因此极端中断时二者可能对应不同的更新进度。
 */
public class SessionLoader {

    /** 回放结果：恢复出的消息条数与待办列表（供 Renderer 渲染「已恢复」提示）。 */
    public record Result(int messageCount, List<TodoItem> todos) {
    }

    /**
     * 把某次会话的日志回放进给定的 {@link Session}，并让它继续往同一个文件追加。
     *
     * <p>步骤：
     * <ol>
     *   <li>读回消息、做中断检测（丢掉悬空工具调用）；</li>
     *   <li>若没有可恢复的消息，<b>不动</b>当前会话、直接返回空结果（避免恢复失败却清空了现有会话）；</li>
     *   <li>清空当前会话内存，并把持久化 sink 重新绑定到被恢复的 sessionId/文件——
     *       这样恢复后的新消息会<b>追加到被恢复的同一个 .jsonl</b>，而不是新建文件；</li>
     *   <li>按顺序把消息放回历史（走 {@code replay}，只改内存、<b>不再触发落盘</b>，
     *       否则会把已在日志里的消息又 append 一遍）；</li>
     *   <li>从快照恢复待办（快照本就是最新态，不必从日志重算）。</li>
     * </ol>
     */
    public Result restore(Session session, String sessionId, SessionStore store, TodoSnapshotStore todoStore) {
        List<Message> messages = dropDanglingToolCalls(store.readAll());
        if (messages.isEmpty()) {
            return new Result(0, List.of());
        }

        session.clear();                                        // 清空当前会话内存（历史/待办/会话级放行）
        session.attachPersistence(sessionId, store, todoStore); // 续写同一文件：后续新消息追加到被恢复的日志
        for (Message message : messages) {
            session.replay(message);                            // 只改内存、不写 sink
        }

        List<TodoItem> todos = todoStore.read();
        session.restoreTodos(todos);
        return new Result(session.size(), todos);
    }

    /**
     * 中断检测：丢掉没有对应结果的「悬空工具调用」。
     *
     * <p>问题场景：上次会话在工具执行中途被中断（Ctrl-C 或程序被杀）——日志里留下一条助手消息
     * （模型说「我要调用 Edit」），但没有对应的工具结果。若原样回放，内存历史里就有一个
     * 「要求调用工具、却没有结果」的悬空调用；再发给模型，大多数模型会报协议错误
     * （工具调用必须配一个结果）。这里把这类悬空调用摘掉，保证重建出的历史对模型合法。
     *
     * <p>做法：先收集所有已出现结果的 toolCallId，再逐条看助手消息，只保留「有结果」的工具调用；
     * 若某条助手消息的工具调用被摘光、又没有文本，整条丢弃。
     *
     * <p>简化：更完整的恢复还会处理更多边角（孤立 thinking、空白助手、半轮补 continue 等）；
     * mini-cc 只做最关键的这一项——「恢复到最新状态」，不追求把被中断的那一轮无缝续上。
     *
     * <p><b>面试追问：是否完整验证了工具消息协议？</b>没有。该算法先扫描全部结果 ID，再扫描助手调用，
     * 时间约为 O(消息数 + 调用/结果数)，使用一个结果 ID 集合换取快速成员查询。
     * 它依赖调用 ID 不重复、日志顺序来自正常追加；不会校验结果是否紧跟所属助手轮次，
     * 也不会删除缺少原调用的孤立工具结果。若日志任意损坏或外部导入，需要更严格的逐轮校验。
     */
    private List<Message> dropDanglingToolCalls(List<Message> messages) {
        // 1) 先收集所有已出现结果的 toolCallId
        Set<String> resolved = messages.stream()
                .filter(message -> message instanceof ToolResponseMessage)
                .flatMap(message -> ((ToolResponseMessage) message).getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::id)
                .collect(Collectors.toSet());

        List<Message> cleaned = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                List<AssistantMessage.ToolCall> calls =
                        assistant.getToolCalls() == null ? List.of() : assistant.getToolCalls();
                // 只保留「有结果」的工具调用
                List<AssistantMessage.ToolCall> kept = calls.stream()
                        .filter(call -> resolved.contains(call.id()))
                        .toList();
                // 工具调用全被摘掉、又没有文本 → 整条助手消息丢弃
                if (kept.isEmpty() && (assistant.getText() == null || assistant.getText().isBlank())) {
                    continue;
                }
                // 调用被摘掉一部分时，换一条只带剩余调用的助手消息；没摘则原样保留
                if (kept.size() == calls.size()) {
                    cleaned.add(assistant);
                } else {
                    // 保留 metadata：里面有思考模式的 reasoningContent，丢掉后恢复的工具调用轮次回传会被拒。
                    cleaned.add(AssistantMessage.builder()
                            .content(assistant.getText() == null ? "" : assistant.getText())
                            .properties(assistant.getMetadata())
                            .toolCalls(kept)
                            .build());
                }
            } else {
                cleaned.add(message);
            }
        }
        return cleaned;
    }
}
