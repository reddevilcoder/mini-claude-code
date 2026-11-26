package com.minicc.checkpoint;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 写工具看到的检查点接口（M10）：Edit/Write 在真正写盘前调用 {@link #before}，写完调用 {@link #after}。
 *
 * <p><b>面试关注：为什么在工具内部、而不是在执行前钩子里备份？</b>
 * 钩子运行在审批之前，那时还不知道这次调用会不会被拒绝、参数校验会不会失败；在钩子里备份会产生大量无用快照，
 * 更糟的是钩子与真正写入之间隔着审批等待，文件可能已被用户改过，备份到的不是“写入前”的内容。
 * 放在工具里“紧挨着写入之前”，备份的就是被覆盖的那一刻的内容；备份失败则直接放弃写入。
 */
public interface FileCheckpoint {

    FileCheckpoint NONE = new FileCheckpoint() {
        @Override
        public void before(Path file) {
        }

        @Override
        public void after(Path file) {
        }
    };

    /** 写入前备份；抛异常表示无法备份，调用方必须放弃写入。 */
    void before(Path file) throws IOException;

    /** 写入成功后记录改后状态。 */
    void after(Path file);

    /** 绑定到某个会话检查点与当前轮次。memoryDir 下的写入（记忆文件）不纳入回退。 */
    static FileCheckpoint of(CheckpointStore store, String turnId, Path excludeDir) {
        if (store == null || !store.enabled() || turnId == null) {
            return NONE;
        }
        Path excluded = excludeDir == null ? null : excludeDir.toAbsolutePath().normalize();
        return new FileCheckpoint() {
            private boolean skip(Path file) {
                return excluded != null && file.toAbsolutePath().normalize().startsWith(excluded);
            }

            @Override
            public void before(Path file) throws IOException {
                if (!skip(file)) {
                    store.beforeWrite(turnId, file);
                }
            }

            @Override
            public void after(Path file) {
                if (!skip(file)) {
                    store.afterWrite(turnId, file);
                }
            }
        };
    }
}
