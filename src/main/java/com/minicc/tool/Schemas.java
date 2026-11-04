package com.minicc.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构造工具参数 JSON Schema 的小工具，减少各工具里拼 Map 的样板代码。
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
