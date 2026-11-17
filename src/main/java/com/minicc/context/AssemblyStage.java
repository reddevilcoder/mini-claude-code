package com.minicc.context;

import com.minicc.session.Session;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 组装阶段：上下文组装管线里的一个步骤，负责往消息列表里追加自己那一部分内容。
 *
 * <p>多个阶段按注册顺序依次执行，共同拼出一次请求的完整输入。这是核心扩展点之一——
 * 后续里程碑要往上下文里加新内容（M4 压缩、M7 Skills、M8 长期记忆），只需新增一个阶段
 * 插进管线，不改已有阶段。思路同 Servlet Filter 链 / Spring 拦截器链，只不过处理对象是消息列表。
 *
 * <p><b>面试关注：扩展点的契约。</b>阶段接收的是同一个可变列表，默认约定按顺序追加，
 * 接口本身并不阻止清空前面内容或修改 {@code session}。因此顺序和副作用由装配代码及实现者保证，
 * 没有并行执行各阶段；否则既会打乱消息顺序，也可能让压缩与读取历史发生竞争。
 */
public interface AssemblyStage {

    /**
     * 把本阶段负责的内容追加到 messages 中。
     *
     * @param messages 正在拼装的消息列表（阶段之间共享、按顺序追加）
     * @param session  当前会话状态
     */
    void contribute(List<Message> messages, Session session);
}
