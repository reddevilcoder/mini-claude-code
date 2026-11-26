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
 *
 * <p><b>面试关注：为什么按名称查表？</b>模型返回的是字符串工具名，注册表把外部协议映射为内部策略对象，
 * 查找平均为 O(1)，无需在 AgentLoop 维护不断增长的 switch。LinkedHashMap 还使工具 Schema 的发送顺序稳定。
 * 本实现假定启动时注册、运行时读取：同名注册会覆盖旧实现，all() 返回底层 values 视图，
 * 也没有并发写保护；动态热插拔时需要额外规定唯一性、只读快照和同步策略。
 */
public class ToolRegistry {

    // 用 LinkedHashMap 保留注册顺序，便于 /tools 命令按固定顺序展示。
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** 注册一个工具。 */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /** 按名字查找工具。 */
    /** M11：注销工具（MCP 重连时替换旧工具）。 */
    public void remove(String name) {
        tools.remove(name);
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 返回所有已注册的工具。 */
    public Collection<Tool> all() {
        return tools.values();
    }
}
