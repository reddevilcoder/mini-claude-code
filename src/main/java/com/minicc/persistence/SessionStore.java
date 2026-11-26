package com.minicc.persistence;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

/**
 * 会话日志：append-only 的 JSONL 读写，是会话历史的唯一真相源。
 *
 * <p>一行就是一条 Spring AI {@link Message}（{@link UserMessage}、{@link AssistantMessage}
 * 或 {@link ToolResponseMessage}），和内存历史用的是同一种类型，落盘和回放不再互相翻译。
 * 写出的 JSON 只保留回放需要的字段，用 {@code messageType} 区分类型，例如
 * {@code {"messageType":"USER","text":"..."}}。
 *
 * <p>Spring AI 的消息类没有无参构造，{@code AssistantMessage.hasToolCalls()} 又和
 * {@code getToolCalls()} 撞了 Jackson 的属性名，不能直接丢给 {@link ObjectMapper}。
 * 所以序列化收在本类里，外面看到的仍是 {@link Message}。
 *
 * <p>写：每追加一条消息就向文件追加一行，避免为新消息重写整个历史；
 * 进程被杀时可能留下不完整的尾行，恢复时按行跳过损坏内容。
 * 读：把整段历史按行读回（供 {@code /resume} 回放）。
 *
 * <p>相比完整实现，这里去掉 uuid 链、跨实例文件锁与异步写队列——mini-cc 的消息频率很低（一次模型往返才几条），
 * 且 Session 本就逐条追加消息，天然一条消息对一行，不需要「去重 + 只写新尾部」那套增量逻辑。
 *
 * <p>与 {@link TodoSnapshotStore} 的分工：本类是「账本」（完整、只增的历史原文），
 * 待办快照是「余额」（最新态，可直接读来渲染当前进度）。
 *
 * <p><b>面试关注：JSONL 的可靠性做到哪一步？</b>
 * <ul>
 *   <li>每条消息独立为一行 JSON，局部损坏可局部跳过，且追加成本无需随历史长度线性增长；
 *       readAll 却仍会一次性把整个文件载入内存，超长会话需要流式读取、分段或索引。</li>
 *   <li>Files.writeString 关闭写入流不等于 fsync；本类没有 FileChannel.force、事务或写前日志，
 *       不能承诺断电不丢数据，也不能保证多条消息成组原子写入。</li>
 *   <li>synchronized 只串行化同一个 SessionStore 对象的 append。不同实例或进程写同一个文件，
 *       没有共同锁；同名文件并不意味着共享同一把 Java 锁。</li>
 *   <li>日志保存的是应用已经接收的消息，不是“恰好执行一次”的操作记录。
 *       工具可能已产生副作用、结果却尚未落盘，恢复时不能据此自动重跑有副作用的工具。</li>
 * </ul>
 */
public class SessionStore {

    /**
     * 思考模式思维链在助手消息 metadata 里的键。与 Spring AI 2.0 回传 {@code reasoning_content} 时读取的键一致。
     */
    public static final String REASONING_CONTENT = "reasoningContent";

    /** M10：用户消息 metadata 里的轮次 ID 键（一条用户输入 = 一轮 = 一个检查点）。 */
    public static final String TURN_ID = "turnId";

    /** M10：回退事件的 messageType。不是消息，是“从某轮起之前的记录作废”的标记。 */
    static final String REWIND = "REWIND";

    private static final TypeReference<List<AssistantMessage.ToolCall>> TOOL_CALLS =
            new TypeReference<>() {
            };
    private static final TypeReference<List<ToolResponseMessage.ToolResponse>> TOOL_RESPONSES =
            new TypeReference<>() {
            };

    private final Path logFile;          // <projectDir>/<sessionId>.jsonl；null 时读写为空操作
    private final ObjectMapper json;     // 消息的 JSON 序列化

    public SessionStore(Path logFile) {
        this.logFile = logFile;
        this.json = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        JsonSerializer serializer = new JsonSerializer();
        module.addSerializer(UserMessage.class, serializer);
        module.addSerializer(AssistantMessage.class, serializer);
        module.addSerializer(ToolResponseMessage.class, serializer);
        module.addDeserializer(Message.class, new JsonDeserializer());
        json.registerModule(module);
    }

    /** 构造一个未绑定文件的存储：所有读写都是空操作（供未接入持久化的 Session 兜底，如单元测试）。 */
    public static SessionStore disabled() {
        return new SessionStore(null);
    }

    /**
     * 追加一条消息：序列化成一行、以 APPEND 方式写入文件（append-only，一行一条消息，不强制 fsync）。
     *
     * <p>错误处理：写盘是「尽力而为」的旁路。遇到 IO 异常（磁盘满、权限不足等）时记一条告警、继续运行，
     * 不抛给控制循环——会话在内存里仍然正常，只是这次没落盘。持久化的任何失败都应降级为「没有持久化」，
     * 而不是让 Agent 用不了。
     */
    public synchronized void append(Message message) {
        if (logFile == null) {
            return;
        }
        try {
            Files.createDirectories(logFile.getParent());     // 目录按需创建
            String line = json.writeValueAsString(message) + "\n";
            Files.writeString(logFile, line, CREATE, APPEND);  // append-only
        } catch (IOException | RuntimeException e) {
            System.err.println("写入会话日志失败（已忽略，会话仍在内存中）：" + e.getMessage());
        }
    }

    /**
     * 读回整段历史：逐行解析成消息列表（供 {@link SessionLoader} 回放）。
     *
     * <p>错误处理：程序在 append 中途被杀可能留下最后一行不完整的 JSON。逐行解析时对解析失败的行
     * 跳过并告警（通常就是末尾那一行），不让整个恢复失败——已完整的前面部分照常恢复。
     */
    public List<Message> readAll() {
        if (logFile == null || !Files.exists(logFile)) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(logFile)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = json.readTree(line);
                    if (REWIND.equals(node.path("messageType").asText())) {
                        truncateBeforeTurn(messages, node.path(TURN_ID).asText());
                        continue;
                    }
                    messages.add(json.treeToValue(node, Message.class));
                } catch (Exception lineError) {
                    System.err.println("跳过损坏的会话日志行：" + lineError.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("读取会话日志失败：" + e.getMessage());
        }
        return messages;
    }

    /**
     * M10：追加一条回退事件——读回时，{@code turnId} 那条用户消息及其之后的记录全部作废。
     *
     * <p><b>面试关注：为什么追加事件而不是改写 JSONL？</b>
     * 日志是 append-only 的事件流：改写文件既有崩溃时写坏整份日志的风险，也会丢掉“曾经发生过什么”的审计信息。
     * 追加一行 REWIND，读回（{@link #readAll}）时按事件重放即可得到回退后的视图，
     * 和数据库的 WAL / 事件溯源是同一个思路。
     */
    public synchronized void appendRewind(String turnId) {
        if (logFile == null) {
            return;
        }
        try {
            Files.createDirectories(logFile.getParent());
            String line = json.writeValueAsString(Map.of("messageType", REWIND, TURN_ID, turnId)) + "\n";
            Files.writeString(logFile, line, CREATE, APPEND);
        } catch (IOException | RuntimeException e) {
            System.err.println("写入回退事件失败：" + e.getMessage());
        }
    }

    /** 把 messages 截断到 turnId 那条用户消息之前；找不到则不动（损坏或跨会话的事件不应清空历史）。 */
    public static void truncateBeforeTurn(List<Message> messages, String turnId) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage u && turnId.equals(u.getMetadata().get(TURN_ID))) {
                messages.subList(i, messages.size()).clear();
                return;
            }
        }
    }

    /** 本存储对应的日志文件；未绑定文件时为 null。 */
    public Path logFile() {
        return logFile;
    }

    /**
     * 把一条消息写成一行 JSON。只写回放要用的字段：类型、文本、工具调用或工具结果。
     * 助手消息额外写入思考模式的 {@code reasoningContent}（非空时），否则恢复会话后再带工具调用
     * 请求思考模式模型可能被拒。其余 metadata、media 不在本格式的保存范围，
     * 消息构造器只补自身默认值，不承诺把这些原始字段完整还原。
     *
     * <p>手写序列化还让磁盘格式不必暴露 SDK 消息类的全部内部结构；反过来，SDK 新增的字段也不会
     * 自动保存。扩展消息能力时须同时检查写入、读取和恢复后的模型请求，不能只验证 JSON 能解析。
     */
    private static final class JsonSerializer extends StdSerializer<Message> {

        private JsonSerializer() {
            super(Message.class);
        }

        @Override
        public void serialize(Message message, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("messageType", message.getMessageType().name());
            if (message instanceof UserMessage user) {
                gen.writeStringField("text", user.getText());
                Object turnId = user.getMetadata().get(TURN_ID);
                if (turnId instanceof String id) {
                    gen.writeStringField(TURN_ID, id);   // M10：轮次 ID，/rewind 用它定位回退点
                }
            } else if (message instanceof AssistantMessage assistant) {
                writeText(gen, assistant.getText());
                Object reasoning = assistant.getMetadata().get(REASONING_CONTENT);
                if (reasoning instanceof String text && !text.isBlank()) {
                    gen.writeStringField(REASONING_CONTENT, text);
                }
                gen.writeFieldName("toolCalls");
                provider.defaultSerializeValue(
                        assistant.getToolCalls() == null ? List.of() : assistant.getToolCalls(), gen);
            } else if (message instanceof ToolResponseMessage tool) {
                gen.writeFieldName("responses");
                provider.defaultSerializeValue(tool.getResponses(), gen);
            } else {
                throw new IllegalArgumentException("不支持落盘的消息类型：" + message.getMessageType());
            }
            gen.writeEndObject();
        }

        private static void writeText(JsonGenerator gen, String text) throws IOException {
            if (text == null) {
                gen.writeNullField("text");
            } else {
                gen.writeStringField("text", text);
            }
        }
    }

    /** 把一行 JSON 读回对应的 {@link Message}。 */
    private static final class JsonDeserializer extends StdDeserializer<Message> {

        private JsonDeserializer() {
            super(Message.class);
        }

        @Override
        public Message deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.readValueAsTree();
            String type = node.path("messageType").asText();
            if (MessageType.USER.name().equals(type)) {
                JsonNode text = node.get("text");
                if (text == null || text.isNull()) {
                    throw context.weirdStringException("", UserMessage.class, "用户消息缺少 text");
                }
                JsonNode turnId = node.get(TURN_ID);
                return UserMessage.builder().text(text.asText())
                        .metadata(turnId == null || turnId.isNull() ? Map.of() : Map.of(TURN_ID, turnId.asText()))
                        .build();
            }
            if (MessageType.ASSISTANT.name().equals(type)) {
                JsonNode text = node.get("text");
                String content = text == null || text.isNull() ? null : text.asText();
                JsonNode reasoning = node.get(REASONING_CONTENT);
                Map<String, Object> metadata = reasoning == null || reasoning.isNull() || reasoning.asText().isBlank()
                        ? Map.of()
                        : Map.of(REASONING_CONTENT, reasoning.asText());
                return AssistantMessage.builder()
                        .content(content)
                        .properties(metadata)
                        .toolCalls(read(parser, node.get("toolCalls"), TOOL_CALLS))
                        .build();
            }
            if (MessageType.TOOL.name().equals(type)) {
                return ToolResponseMessage.builder()
                        .responses(read(parser, node.get("responses"), TOOL_RESPONSES))
                        .build();
            }
            throw context.weirdStringException(type, Message.class, "未知的 messageType");
        }

        private static <T> List<T> read(JsonParser parser, JsonNode node, TypeReference<List<T>> type) {
            if (node == null || node.isNull() || !node.isArray()) {
                return List.of();
            }
            ObjectMapper mapper = (ObjectMapper) parser.getCodec();
            return mapper.convertValue(node, type);
        }
    }
}
