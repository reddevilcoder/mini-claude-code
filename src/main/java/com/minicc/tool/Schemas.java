package com.minicc.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构造工具参数 JSON Schema 的小工具，减少各工具里拼 Map 的样板代码。
 *
 * <p><b>面试关注：协议描述与执行校验分层。</b>这里仅生成发送给模型的结构化参数说明，
 * 并没有在本地运行 JSON Schema 校验器，也未设置严格拒绝额外字段。required/enum 能引导生成，
 * 真实执行仍必须处理缺失、越界和不符合业务含义的参数，不能把模型遵守 Schema 当作程序正确性的前提。
 */
public final class Schemas {

    private Schemas() {
    }

    /** 构造一个 object 类型的参数 Schema。 */
    public static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    /** 构造一个属性定义（类型 + 说明）。 */
    public static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    /** 构造一个带可选值枚举的属性定义。 */
    public static Map<String, Object> prop(String type, String description, List<String> enumValues) {
        Map<String, Object> p = prop(type, description);
        p.put("enum", enumValues);
        return p;
    }
}
