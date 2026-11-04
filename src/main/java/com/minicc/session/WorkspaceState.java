package com.minicc.session;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作区状态：记录本次会话"读过哪些文件"，供 Edit / Write 做"改前先读"校验。
 *
 * <p>Edit/Write 有一条硬性约束——修改一个已有文件之前，必须在本次会话里先 Read 过它，
 * 否则工具直接报错。它的意义有两点：一是逼模型基于文件真实内容来构造修改，而不是凭记忆瞎改；
 * 二是 old_string 这种精确匹配必须以读到的原文为准，先读才能拿到准确的缩进和上下文。
 *
 * <p>一个会话一个实例，由 {@link Session} 持有、通过 ToolContext 传给工具。
 * 因为只读工具可并行执行（见 ToolScheduler），这里用线程安全集合。
 *
 * <p>进阶说明：更完整的实现还会记录内容与 mtime，能检测"读完后文件又被外部改动过"。
 * mini-cc 的 WorkspaceState 只记"读过没读过"，这对教学足够；外部改动检测留作进阶。
 */
public class WorkspaceState {

    private final Set<Path> readFiles = ConcurrentHashMap.newKeySet();

    /** 登记一个已读文件（Read 成功后调用）。路径统一规整为绝对路径，避免相对/绝对不一致。 */
    public void markRead(Path file) {
        readFiles.add(normalize(file));
    }

    /** 这个文件本会话是否被 Read 过。 */
    public boolean hasRead(Path file) {
        return readFiles.contains(normalize(file));
    }

    private static Path normalize(Path file) {
        return file.toAbsolutePath().normalize();
    }
}
