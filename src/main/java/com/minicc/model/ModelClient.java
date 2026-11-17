package com.minicc.model;

import com.minicc.config.MccConfig;
import com.minicc.tool.Tool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Collection;
import java.util.List;

/**
 * 模型边界：整个 harness 与模型交互的唯一入口。
 *
 * <p>它只做一件事：拿到已经拼好的消息和本次可用工具，调用模型，原样返回
 * {@link AssistantMessage}。上下文组装由 {@code ContextAssembler} 完成，助手消息写回会话由
 * {@code Session} 完成，两者都由控制循环编排，不进入本类。历史里留的是模型实际返回的那条消息。
 * 控制循环和落盘都直接使用这条消息上的 {@link AssistantMessage.ToolCall}；
 * 按字段读参数由 {@code ToolCallHelper} 完成。
 *
 * <p>Spring AI 2.0 的 {@code ChatModel.call} 不再在框架里执行工具，模型要求的工具调用留在返回的
 * {@link AssistantMessage} 上，由我们自己的控制循环来分发和回填。思考模式的思维链放在这条消息的
 * metadata（键 {@code reasoningContent}）里；下一轮请求时 Spring AI 会把它作为 {@code reasoning_content}
 * 回传。调用方只要原样保存这条助手消息即可。
 *
 * <p><b>面试关注：为什么把模型调用单独封装？</b>
 * 这是适配器边界：上层只依赖“消息 + 工具定义 → 助手消息”，
 * 不把审批、文件操作、持久化耦合到模型 SDK。测试可注入假的 ChatModel，
 * 用确定的返回序列验证 Agent 循环，而不需要真实 API Key、网络和随机模型输出。
 *
 * <p><b>面试关注：Function Calling 是否会直接执行函数？</b>
 * 这里传入的 ToolCallback 仅描述能力，模型返回的是工具名、调用 ID 和 JSON 参数；
 * 真正执行由 AgentLoop / ToolScheduler 控制。原样保留 AssistantMessage 也保留了工具调用与 metadata，
 * 避免只保存 getText() 后丢失后续协议需要的信息。
 *
 * <p><b>面试追问：重试、流式和取消在哪里？</b>
 * 本类只使用同步 call，没有在 harness 这一层实现流式消费、重试、超时或请求取消，
 * 底层 SDK 的默认行为也不应当作这里显式实现的能力。异常向上交给控制循环处理；
 * 若增加重试，应区分仅重发模型请求与重新执行有副作用的工具，避免重复写入。
 */
public class ModelClient {

    private final ChatModel chatModel;
    private final MccConfig config;

    /** 按配置构建 OpenAI 兼容的聊天模型。调用方只传配置，不必接触 Spring AI 的模型类型。 */
    public ModelClient(MccConfig config) {
        this(buildChatModel(config), config);
    }

    /** 注入已有的 {@link ChatModel}（测试用替身）。 */
    public ModelClient(ChatModel chatModel, MccConfig config) {
        this.chatModel = chatModel;
        this.config = config;
    }

    private static ChatModel buildChatModel(MccConfig config) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey() == null ? "" : config.apiKey())
                .model(config.model())
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }

    /**
     * 用给定的消息和工具请求模型一轮。
     *
     * @param messages 本次请求的完整消息（已由上下文管线拼好）
     * @param tools    本次可用的工具（转成工具定义随请求发送）
     * @return 模型返回的助手消息，不在这里改写
     */
    public AssistantMessage call(List<Message> messages, Collection<Tool> tools) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.model())
                .toolCallbacks(ToolsConverter.toToolCallbacks(tools))
                .build();

        ChatResponse resp = chatModel.call(new Prompt(messages, options));
        // 这里取首个生成结果，约定模型响应至少包含一个结果；没有在适配器内拼接多候选或执行工具。
        return resp.getResult().getOutput();
    }
}
