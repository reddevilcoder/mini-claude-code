package com.minicc.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.Map;

/**
 * {@link AssistantMessage.ToolCall} 的参数是 JSON 字符串。工具、审批和渲染要按字段取值，
 * 集中在这里解析；测试里从已解析的 Map 造一条调用，也走这里，避免各处自己拼 JSON。
 *
 * <p><b>面试关注：Schema 能保证参数合法吗？</b>不能。这里做宽松解析和类型转换，各工具仍需检查必填项、
 * 路径、范围及业务约束。非法 JSON 退化为空参数，通常由工具返回“缺少参数”；这提高容错性，也会丢失原始解析原因。
 * 每次 get 方法都会重新解析 JSON，多个字段不是一次解析的缓存；当前参数较小，若调用频繁可再引入单次解析的参数对象。
 * ObjectMapper 在静态初始化后不再修改配置，可复用于并行调用。
 */
public final class ToolCallHelper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolCallHelper() {
    }

    /**
     * 用已解析的参数造一条框架工具调用。type 固定为 {@code "function"}，与模型返回的一致。
     */
    public static AssistantMessage.ToolCall of(String id, String name, Map<String, Object> arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, toJson(arguments));
    }

    /**
     * 把 arguments JSON 解析成 Map。空白或非法 JSON 按空参数处理，不让一次坏参数打断循环。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> arguments(AssistantMessage.ToolCall call) {
        if (call == null) {
            return Map.of();
        }
        String argumentsJson = call.arguments();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(argumentsJson, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 读取字符串参数，不存在时返回默认值。 */
    public static String getString(AssistantMessage.ToolCall call, String key, String defaultValue) {
        Object v = arguments(call).get(key);
        return v == null ? defaultValue : String.valueOf(v);
    }

    /** 读取整数参数，不存在或非法时返回默认值。 */
    public static Integer getInt(AssistantMessage.ToolCall call, String key, Integer defaultValue) {
        Object v = arguments(call).get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 读取布尔参数，不存在时返回默认值。 */
    public static boolean getBool(AssistantMessage.ToolCall call, String key, boolean defaultValue) {
        Object v = arguments(call).get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }

    /** 把参数 Map 序列化成框架需要的 arguments JSON。 */
    private static String toJson(Map<String, Object> arguments) {
        try {
            return MAPPER.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception e) {
            return "{}";
        }
    }
}
