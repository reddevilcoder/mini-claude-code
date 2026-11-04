package com.minicc.context;

import com.minicc.session.Session;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 阶段二：放入会话历史——按时间顺序追加用户消息、助手消息和工具结果观察。
 *
 * <p>M4 的压缩会在这个阶段之前（或之内）对历史做裁剪，但那是后续里程碑的事；
 * M1 直接把完整历史放进去。
 */
public class HistoryStage implements AssemblyStage {

    @Override
    public void contribute(List<Message> messages, Session session) {
        messages.addAll(session.history());
    }
}
