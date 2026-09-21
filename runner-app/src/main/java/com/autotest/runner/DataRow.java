package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** 一次接口执行所使用的、经过结构校验的数据行。 */
record DataRow(String id, boolean enabled, Map<String, JsonNode> values) {

    DataRow {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("数据行 id 不能为空");
        }
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> copy.put(key, value == null ? null : value.deepCopy()));
        }
        values = Map.copyOf(copy);
    }

    static DataRow empty() {
        return new DataRow("default", true, Map.of());
    }
}
