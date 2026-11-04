package com.minicc.context;

import com.minicc.session.Session;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.List;

/**
 * 阶段一：放入系统提示词。
 *
 * <p>系统提示词是固定模板，始终排在消息列表最前面。
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
