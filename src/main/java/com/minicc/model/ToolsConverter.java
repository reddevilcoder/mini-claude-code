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
 *
 * <p><b>面试关注：这是适配器模式，还是工具执行器？</b>
 * 它只把项目接口翻译成 SDK 所需的定义，不执行工具；call 主动抛异常用于暴露意外的框架执行路径。
 * 这样审批钩子、并发调度、取消与结果落盘都能经过同一个 harness 入口。
 *
 * <p><b>面试追问：JSON Schema 能保证参数一定合法吗？</b>
 * Schema 向模型声明参数结构，不等于应用端已经完成校验或获得授权。
 * 工具仍需检查必填字段、绝对路径、业务约束，权限检查仍由执行前钩子负责。
 * 工具描述和 Schema 也会占用请求上下文，不能只计算对话文本的 Token 成本。
 */
public final class ToolsConverter {

    // 配置完成后只做序列化可共享实例；不要在并发使用过程中继续修改它的配置。
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
