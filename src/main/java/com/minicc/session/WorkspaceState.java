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
 *
 * <p><b>面试关注：线程安全与业务正确性是两件事。</b>ConcurrentHashMap 的 key-set 保证并行 Read
 * 登记、查询时集合本身不被破坏；它不保证“读文件→检查→写文件”这一串文件系统操作原子化。
 * 当前没有保存读取版本、mtime 或内容哈希，所以已读后被外部修改的文件仍可能通过校验。
 * 若要防止覆盖他人修改，需要版本比较和更完整的写入并发控制，而不只是换一个并发集合。
 *
 * <p>路径的 normalize 只消去 {@code .}/{@code ..} 等语法差异，不解析符号链接，
 * 不同路径仍可能指向同一文件；这个集合既不是文件身份的完整索引，也不是文件访问权限边界。
 * M10 起 {@code /clear} 会清空该集合；{@code /rewind} 还原文件后会撤销被还原文件的已读标记，逼模型重新读取。
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

    /** M12：拷贝一份给子代理：子代理继承“主代理已读”，但它自己读过的不回流主代理（主模型并没看到内容）。 */
    public WorkspaceState copy() {
        WorkspaceState c = new WorkspaceState();
        c.readFiles.addAll(readFiles);
        return c;
    }

    /** 清空全部已读标记（/clear）。 */
    public void clear() {
        readFiles.clear();
    }

    /** 撤销某个文件的已读标记（/rewind 还原文件后，模型记忆中的内容已过期）。 */
    public void forget(Path file) {
        readFiles.remove(normalize(file));
    }

    private static Path normalize(Path file) {
        return file.toAbsolutePath().normalize();
    }
}
