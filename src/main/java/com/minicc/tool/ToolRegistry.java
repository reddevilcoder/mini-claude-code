package com.minicc.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表：按名字登记和查找工具。
 *
 * <p>这是核心扩展点之一——后续里程碑新增工具，只需在启动时 {@link #register(Tool)} 一个实现，
 * 控制循环的分发逻辑完全不用改。
 */
public class ToolRegistry {

    // 用 LinkedHashMap 保留注册顺序，便于 /tools 命令按固定顺序展示。
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** 注册一个工具。 */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /** 按名字查找工具。 */
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 返回所有已注册的工具。 */
    public Collection<Tool> all() {
        return tools.values();
    }
}
