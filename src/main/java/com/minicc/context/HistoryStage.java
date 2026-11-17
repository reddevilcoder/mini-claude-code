package com.minicc.context;

import com.minicc.session.Session;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 阶段二：放入会话历史——按时间顺序追加用户消息、助手消息和工具结果观察。
 *
 * <p>这是早期版本和不需要压缩场景下的实现：直接把完整历史放进去。
 * 当前应用入口用 {@link CompactionStage} 替代它；二者应按需择一注册，不能一起追加同一份历史。
 */
public class HistoryStage implements AssemblyStage {

    @Override
    public void contribute(List<Message> messages, Session session) {
        messages.addAll(session.history());
    }
}
