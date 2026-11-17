package com.minicc.context;

import com.minicc.session.Session;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.List;

/**
 * 阶段一：放入系统提示词。
 *
 * <p>系统提示词是固定模板，始终排在消息列表最前面。
 *
 * <p><b>面试关注：系统消息与用户消息的职责。</b>固定行为规则放在靠前的系统消息，
 * 用户任务保留在历史中，避免每轮把规则拼进用户文本。系统消息能引导模型，但不是本地安全边界；
 * 是否允许执行命令和改文件仍由调度器及工具校验决定。
 */
public class SystemPromptStage implements AssemblyStage {

    private final String systemPrompt;

    public SystemPromptStage(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    @Override
    public void contribute(List<Message> messages, Session session) {
        messages.add(new SystemMessage(systemPrompt));
    }
}
