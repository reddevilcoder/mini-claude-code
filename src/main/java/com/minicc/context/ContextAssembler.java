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
 *
 * <p><b>面试关注：为什么拆成管线，顺序为什么不能随便换？</b>
 * 每个 {@link AssemblyStage} 只贡献一类上下文，增加记忆或技能时，装配入口选择顺序即可，
 * 主循环不需要认识这些细节。这是组合与策略扩展的用法；这里没有“调用下一个阶段”的回调，
 * 实际驱动者是本类的顺序遍历，不要把它说成带短路语义的权限责任链。
 *
 * <p>当前入口按“系统提示词 → 记忆 → 技能清单 → 计划态提示 → 压缩后的历史”装配。
 * 稳定信息靠前能增加共享前缀的机会，但是否缓存、缓存多久由模型服务决定，客户端不能保证命中。
 * 每次新建输出列表只隔离本次组装结果；阶段仍可能有副作用，例如压缩阶段可能请求摘要并替换内存历史，
 * 因此 {@code assemble} 不是纯函数，也不能直接用于一个保证无副作用的“预览”。
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
