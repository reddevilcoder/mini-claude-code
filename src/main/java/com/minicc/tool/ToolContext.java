package com.minicc.tool;

import com.minicc.agent.CancelToken;
import com.minicc.permission.PermissionState;
import com.minicc.plan.PlanModeState;
import com.minicc.session.TodoList;
import com.minicc.session.WorkspaceState;

import java.nio.file.Path;

/**
 * 工具执行上下文：工具执行时需要的环境信息。
 *
 * <p>M1 只需要工作目录。M2 按 M1 预留的方式扩展，加入：
 * <ul>
 *   <li>{@link WorkspaceState}：记录本会话读过哪些文件，供 Edit/Write 的"改前先读"校验；</li>
 *   <li>{@link TodoList}：本次任务的待办清单，供 TodoWrite 更新；</li>
 *   <li>sandbox：是否把 Bash 命令包进 OS 沙箱；</li>
 *   <li>bashTimeoutMs：Bash 默认超时（毫秒）。</li>
 * </ul>
 *
 * <p>M3 继续扩展（都是 M1 早已预告的位置）：
 * <ul>
 *   <li>{@link CancelToken}：协作式取消信号，工具在执行中按需查询（M1 注释已预告 Ctrl-C 会在这里接上）；</li>
 *   <li>{@link PermissionState}：会话级放行状态，由 {@code Session} 持有、经此下发给审批钩子
 *       （与 WorkspaceState 同一套路）。</li>
 * </ul>
 *
 * <p>M5 再次扩展（同样是 M1 预告过的位置）：加入长期记忆目录信息，供审批钩子判断
 * 「写入是否落在记忆目录内」。
 * <ul>
 *   <li>{@code memoryDir}：本项目的记忆目录（{@code ~/.mcc/projects/<项目>/memory/}）。
 *       写操作的目标落在它之下时，是 Agent 自己的笔记，审批钩子直接放行、不弹卡片。为空表示未启用记忆。</li>
 *   <li>{@code memoryWritesOnly}：是否处于「每轮结束补充提取」的受限回合。为 true 时，
 *       审批钩子只放行记忆目录内的写入，写到别处一律拒绝（提取不碰用户代码）。</li>
 * </ul>
 *
 * <p>M8 又一次扩展（同一处「按需扩展上下文」的位置，{@code permissions}/{@code memoryDir} 都是这么加进来的）：
 * 加一个 {@link PlanModeState}，把计划态下发给 {@code PlanModeHook}（判断「现在能不能写」）与
 * {@code PlanModeStage}（判断「要不要注入计划态提示」）。它和 {@code permissions} 平级、互不耦合。
 *
 * <p><b>面试关注：record 是否意味着上下文完全不可变？</b>record 固定的是字段引用，workspace、todos、
 * permissions、planMode 指向的对象仍可变化。多个并行工具拿到同一份上下文，共享状态必须由状态类保证并发安全，
 * 或由调度器保证串行访问。显式传递这些依赖比静态全局变量更容易隔离会话、构造测试和实现受限的记忆提取回合。
 *
 * @param workingDir      当前会话针对的项目目录（相对路径以它为基准、检索默认在它之下进行）
 * @param workspace       工作区状态（改前先读校验）
 * @param todos           待办清单（工作状态）
 * @param sandbox         是否把 Bash 命令包进沙箱
 * @param bashTimeoutMs   Bash 默认超时（毫秒）
 * @param cancelToken     协作式取消信号（M3）
 * @param permissions     会话级放行状态（M3，Session 持有，这里下发给审批钩子）
 * @param planMode        计划态开关（M8，Session 持有，这里下发给 PlanModeHook）
 * @param memoryDir       本项目的记忆目录（M5；为空表示未启用记忆）
 * @param memoryWritesOnly 是否为「仅限记忆目录写入」的提取回合（M5）
 * @param checkpoint      文件检查点（M10）：Edit/Write 写盘前备份、写盘后记录哈希
 */
public record ToolContext(
        Path workingDir,
        WorkspaceState workspace,
        TodoList todos,
        boolean sandbox,
        long bashTimeoutMs,
        CancelToken cancelToken,
        PermissionState permissions,
        PlanModeState planMode,
        Path memoryDir,
        boolean memoryWritesOnly,
        com.minicc.checkpoint.FileCheckpoint checkpoint) {

    /** 规范构造器：checkpoint 为 null 时视为不做检查点。 */
    public ToolContext {
        if (checkpoint == null) {
            checkpoint = com.minicc.checkpoint.FileCheckpoint.NONE;
        }
    }

    /** M8 的 10 参签名：不带检查点。 */
    public ToolContext(Path workingDir, WorkspaceState workspace, TodoList todos,
                       boolean sandbox, long bashTimeoutMs, CancelToken cancelToken,
                       PermissionState permissions, PlanModeState planMode, Path memoryDir, boolean memoryWritesOnly) {
        this(workingDir, workspace, todos, sandbox, bashTimeoutMs, cancelToken, permissions, planMode,
                memoryDir, memoryWritesOnly, null);
    }

    /**
     * 兼容旧签名（无记忆目录、无计划态）：M1–M4 的调用点与单元测试继续用这个 7 参构造，
     * {@code planMode} 默认为一个全新（非计划态）的开关、{@code memoryDir} 默认为空、
     * {@code memoryWritesOnly} 默认为 false。于是这些调用点的 {@code PlanModeHook} 一律放行。
     */
    public ToolContext(Path workingDir, WorkspaceState workspace, TodoList todos,
                       boolean sandbox, long bashTimeoutMs, CancelToken cancelToken,
                       PermissionState permissions) {
        this(workingDir, workspace, todos, sandbox, bashTimeoutMs, cancelToken, permissions,
                new PlanModeState(), null, false);
    }

    /**
     * 兼容 M5 的 9 参签名（无计划态）：记忆提取等调用点仍可用这个构造，
     * {@code planMode} 默认为一个全新（非计划态）的开关。
     */
    public ToolContext(Path workingDir, WorkspaceState workspace, TodoList todos,
                       boolean sandbox, long bashTimeoutMs, CancelToken cancelToken,
                       PermissionState permissions, Path memoryDir, boolean memoryWritesOnly) {
        this(workingDir, workspace, todos, sandbox, bashTimeoutMs, cancelToken, permissions,
                new PlanModeState(), memoryDir, memoryWritesOnly);
    }

    /** 在既有上下文上挂一个记忆目录（M5；审批钩子据此自动放行记忆写入）。透传计划态。 */
    public ToolContext withMemoryDir(Path memoryDir) {
        return new ToolContext(workingDir, workspace, todos, sandbox, bashTimeoutMs,
                cancelToken, permissions, planMode, memoryDir, false, checkpoint);
    }

    /**
     * 构造「提取专用」上下文：换一套干净的工作区状态（供提取内部的 Read→Edit 校验），
     * 并把 {@code memoryWritesOnly} 置为 true（只允许写记忆目录，其余写入一律拒绝）。透传计划态。
     */
    public ToolContext memoryOnlyWrites(Path memoryDir, WorkspaceState freshWorkspace) {
        return new ToolContext(workingDir, freshWorkspace, todos, sandbox, bashTimeoutMs,
                cancelToken, permissions, planMode, memoryDir, true, null);
    }
}
