package com.minicc.context;

import com.minicc.session.Session;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文组装管线：按注册顺序依次执行各阶段，拼出一次请求的完整消息列表。
 *
 * <p>固定顺序的好处是靠前的部分稳定，便于模型侧的前缀缓存。
 * M1 注册两个阶段：先系统提示词、再会话历史。
 */
public class ContextAssembler {

    private final List<AssemblyStage> stages; // 有序；M4/M7/M8 往这里插新阶段

    public ContextAssembler(List<AssemblyStage> stages) {
        this.stages = stages;
    }

    /** 依次让每个阶段贡献自己那一段，拼出完整消息列表。 */
    public List<Message> assemble(Session session) {
        List<Message> messages = new ArrayList<>();
        for (AssemblyStage stage : stages) {
            stage.contribute(messages, session);
        }
        return messages;
    }
}
