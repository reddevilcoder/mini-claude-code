package com.minicc.session;

import com.minicc.permission.PermissionState;
import com.minicc.plan.PlanModeState;
import com.minicc.persistence.SessionStore;
import com.minicc.persistence.TodoSnapshotStore;
import com.minicc.tool.ToolResult;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话状态：保存一次会话的消息历史和工作目录。
 *
 * <p>历史直接用 Spring AI 的 {@link Message} 承载。模型返回的 {@link AssistantMessage}
 * 原样写入历史，工具调用也直接用这条消息上的 {@link AssistantMessage.ToolCall}。
 * 控制循环从同一条消息取出调用再派发；按字段读参数走 {@link com.minicc.tool.ToolCallHelper}。
 * 日志里存的也是这几种消息，回放时 {@link #replay} 原样放回内存。
 *
 * <p>M4：Session 旁挂一个持久化 sink。{@code appendUserMessage} / {@code appendAssistant} /
 * {@code appendToolResult} 在改内存的同时，把同一条消息追加进日志（{@link SessionStore}），
 * 更新待办后写一次最新态快照（{@link TodoSnapshotStore}）。所有对历史的追加都收口在这三个方法，
 * 在这里接 sink 就能覆盖全部写入。
 *
 * <p>两条路径的区别：正常 {@code append*} 既改内存<b>又落盘</b>；回放用的 {@link #replay}
 * 只改内存<b>不落盘</b>（否则会把已在日志里的消息又 append 一遍）。
 *
 * <p><b>面试关注：会话状态的边界与一致性。</b>
 * <ul>
 *   <li>history 是模型可见的短期对话；workspace、todos、permissions、planMode 是工具运行状态，
 *       不会因为把历史交给模型就自动恢复。长期记忆另存为文件，生命周期也不同。</li>
 *   <li>追加顺序是先改内存、后尽力写日志，不是一个事务。日志失败时当前对话继续可用，
 *       但重启只能恢复成功落盘的部分；这体现了交互可用性优先的取舍。</li>
 *   <li>本类按 REPL 单控制线程访问设计，并非线程安全容器。并行只读工具只共享其所需的
 *       WorkspaceState，不直接追加 history；不能因为日志方法有 synchronized 就并发调用整个 Session。</li>
 *   <li>恢复只回放消息和待办快照，不重放文件修改、命令等副作用，也不恢复已授予的会话权限。
 *       “恢复聊天记录”不等于“恢复操作系统到当时状态”。</li>
 * </ul>
 */
public class Session {

    private final Path workingDir;
    private final List<Message> history = new ArrayList<>();
    // M2 新增：会话级工作状态。工作区状态供"改前先读"校验，待办清单跟踪多步任务进度。
    private final WorkspaceState workspace = new WorkspaceState();
    private final TodoList todos = new TodoList();
    // M3 新增：会话级放行状态（随会话存在，/clear 时重建）。经 ToolContext 下发给审批钩子。
    private PermissionState permissions = new PermissionState();
    // M8 新增：会话级计划态开关（和 permissions 平级、互不耦合）。经 ToolContext 下发给 PlanModeHook/PlanModeStage。
    private PlanModeState planMode = new PlanModeState();

    // M4 新增：持久化 sink 与会话标识。未接入持久化时用 disabled 的空操作 sink 兜底（如单元测试）。
    private String sessionId;
    private SessionStore store = SessionStore.disabled();
    private TodoSnapshotStore todoStore = TodoSnapshotStore.disabled();

    public Session(Path workingDir) {
        this.workingDir = workingDir;
    }

    /**
     * 接入/切换持久化 sink（M4）。
     *
     * <p>用于三处：① 启动时注入本次会话的日志与快照；② {@code /clear} 时切到新的 sessionId 与新文件
     * （旧日志留在磁盘、仍可 {@code /resume}）；③ {@code /resume} 时绑定到被恢复的那个文件，
     * 让后续新消息继续追加到同一个 .jsonl。
     */
    public void attachPersistence(String sessionId, SessionStore store, TodoSnapshotStore todoStore) {
        this.sessionId = sessionId;
        this.store = store == null ? SessionStore.disabled() : store;
        this.todoStore = todoStore == null ? TodoSnapshotStore.disabled() : todoStore;
    }

    /** 本次会话的标识（UUID）；未接入持久化时为 null。 */
    public String sessionId() {
        return sessionId;
    }

    /** 当前会话针对的项目目录。 */
    public Path workingDir() {
        return workingDir;
    }

    /** 本会话的工作区状态（记录读过哪些文件）。 */
    public WorkspaceState workspace() {
        return workspace;
    }

    /** 本会话的待办清单（工作状态）。 */
    public TodoList todos() {
        return todos;
    }

    /** 本会话的放行状态（会话级权限，经 ToolContext 下发给审批钩子）。 */
    public PermissionState permissions() {
        return permissions;
    }

    /** 本会话的计划态开关（经 ToolContext 下发给 PlanModeHook / PlanModeStage）。 */
    public PlanModeState planMode() {
        return planMode;
    }

    /**
     * 返回消息列表的只读快照（供 HistoryStage 组装）。复制的是列表结构，不会深拷贝每条 Message；
     * 因而是防止调用方增删历史的封装手段，不是并发快照或消息对象深度不可变性的保证。
     */
    public List<Message> history() {
        return List.copyOf(history);
    }

    /** 追加一条用户消息，并落盘同一条 {@link UserMessage}。 */
    public void appendUserMessage(String text) {
        UserMessage message = new UserMessage(text);
        history.add(message);
        store.append(message);
    }

    /**
     * 追加模型这一轮的助手消息，并落盘同一条消息。
     *
     * <p>历史和日志里都是模型返回的那条 {@link AssistantMessage}。工具调用就是消息上的
     * {@link AssistantMessage.ToolCall}（arguments 仍是 JSON 字符串）。
     */
    public void appendAssistant(AssistantMessage message) {
        history.add(message);
        store.append(message);
    }

    /**
     * 把一次工具执行结果作为观察追加到历史，并落盘同一条 {@link ToolResponseMessage}。
     * 在这里把项目自己的 {@link ToolResult} 转成框架的工具响应；{@code error} 供上层判断和渲染，
     * 不进消息本身（错误说明已经写在 content 里）。
     */
    public void appendToolResult(AssistantMessage.ToolCall call, ToolResult result) {
        appendToolResult(toToolResponse(call, result));
    }

    /**
     * 追加一条已构造好的工具结果消息，并落盘同一条（M6 新增的入库口）。
     *
     * <p>M6 的第①层"工具结果预算"在这里之前介入：控制循环先用
     * {@link com.minicc.compact.CompactionManager#offloadToolResult} 把超阈值的大结果落盘、
     * 换成"预览+路径"，再把<b>那条</b>传进来。于是内存历史与 JSONL 存的都是瘦身后的这份
     * （完整原文在 {@code tool-results/<id>.txt}）。
     */
    public void appendToolResult(ToolResponseMessage message) {
        history.add(message);
        store.append(message);
    }

    /**
     * 把工具调用 + 结果构造成一条框架 {@link ToolResponseMessage}（M6：入库前先过第①层落盘）。
     * 配对靠 call.id 而非工具名：同一轮可能多次调用 Read，单靠名字无法判断哪个结果属于哪次调用。
     * 工具失败或被拒绝也要有响应，模型才能在协议完整的历史上纠正下一步行动。
     */
    public static ToolResponseMessage toToolResponse(AssistantMessage.ToolCall call, ToolResult result) {
        return ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponseMessage.ToolResponse(call.id(), call.name(), result.content())))
                .build();
    }

    /**
     * 更新待办后整份覆盖写一次快照（M4）。
     *
     * <p>待办的完整历史已经随 TodoWrite 的助手消息和工具结果进了日志；这里额外写一份「只放当前列表」的
     * 快照，供 {@code /resume} 和 UI 一步读出当前进度。由 AgentLoop 在一次 TodoWrite 生效后调用。
     */
    public void writeTodoSnapshot() {
        todoStore.write(todos.items());
    }

    /**
     * 回放：把日志里读出的一条消息放回历史。
     *
     * <p>与 {@code append*} 的区别：这里<b>只改内存、不写 sink</b>。消息本就是从日志读出来的，
     * 再 append 一遍会重复。
     */
    public void replay(Message message) {
        history.add(message);
    }

    /**
     * 用压缩后的消息<b>整体替换内存历史</b>，但<b>不写 JSONL</b>（M6 第⑤层自动压缩用）。
     *
     * <p>和 {@link #replay} 一样<b>只改内存、不落盘</b>，区别是它先清空再整体替换（replay 是逐条追加）。
     * 自动压缩把较早历史用模型摘要成一条消息后，连同最近几条原文一起替换掉内存 {@code messages}，
     * 这样内存历史从此变瘦、后续请求不必每轮重复摘要（就地改写内存里的 REPL 消息数组）。
     *
     * <p><b>刻意不动 JSONL</b>：完整原文仍在会话日志（JSONL）与 {@code tool-results/} 里，
     * {@code /resume} 读回的仍是未经 ②–⑤ 压缩的全量记录——这正是"压得再狠也不丢原文"的安全网
     * （mini-cc 刻意不把压缩边界+摘要写进 transcript）。
     */
    public void replaceHistoryInMemory(List<Message> messages) {
        history.clear();
        history.addAll(messages);
    }

    /** 回放待办（M4）：用快照里的最新态整体替换当前待办。 */
    public void restoreTodos(List<TodoItem> items) {
        todos.replace(items);
    }

    /**
     * 清空会话历史与工作状态，重新开始。会话级放行状态也随新会话重建（上次放行不延续）。
     *
     * <p>M4：这里只清内存；持久化 sink 的切换由上层负责（{@code /clear} 会在清空后
     * {@link #attachPersistence} 到一个新的 sessionId 与新日志文件，旧日志留在磁盘、仍可 {@code /resume}）。
     *
     * <p>当前实现的边界：workspace 是 final 且这里没有清空其已读集合，因此复用同一个 Session 时，
     * 先前的“已读”标记仍会保留。不能据此声称 /clear 或 /resume 会强制所有文件重新 Read。
     */
    public void clear() {
        history.clear();
        todos.clear();
        permissions = new PermissionState();
        planMode = new PlanModeState();   // M8：计划态随新会话复位为关闭
    }

    /** 当前历史消息条数（供 /context 粗略展示、/resume 报告已恢复条数）。 */
    public int size() {
        return history.size();
    }
}
