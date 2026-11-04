package com.minicc.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minicc.tool.Tool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 把项目自己的 {@link Tool} 转成 Spring AI 的 {@link ToolCallback}。
 *
 * <p>转换结果只承载工具定义（名称、描述、参数 Schema），不承载执行。框架自动执行已关闭，
 * 工具由控制循环自己分发，因此这里的 {@code call} 正常不会被调用。
 */
public final class ToolsConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolsConverter() {
    }

    /** 把本次可用的工具转成随请求发送的工具定义。 */
    public static List<ToolCallback> toToolCallbacks(Collection<Tool> tools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : tools) {
            ToolDefinition def = ToolDefinition.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .inputSchema(toJsonString(tool.paramsSchema()))
                    .build();
            // 只把工具定义交给 Spring AI。框架自动执行已关闭，call 正常不会被调用。
            callbacks.add(new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return def;
                }

                @Override
                public String call(String toolInput) {
                    throw new UnsupportedOperationException(
                            "工具 " + def.name() + " 由 mini Claude Code 自己的控制循环执行，不应由框架直接调用。");
                }
            });
        }
        return callbacks;
    }

    /** 把参数 Schema（Map）序列化成 JSON 字符串。 */
    private static String toJsonString(Map<String, Object> schema) {
        try {
            return MAPPER.writeValueAsString(schema);
        } catch (Exception e) {
            // Schema 由我们自己构造，正常不会失败；兜底返回一个空对象 Schema。
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }
}
