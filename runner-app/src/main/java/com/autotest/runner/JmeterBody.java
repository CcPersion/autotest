package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** 单接口请求体的最小可复用输入。 */
public record JmeterBody(String type, JsonNode value) {

    public JmeterBody {
        type = Objects.requireNonNull(type, "body.type 不能为空").toUpperCase();
        if (!type.equals("NONE") && !type.equals("JSON") && !type.equals("TEXT")
                && !type.equals("URLENCODED") && !type.equals("MULTIPART")) {
            throw new IllegalArgumentException("不支持的 Body 类型: " + type);
        }
        if (!type.equals("NONE") && value == null) {
            throw new IllegalArgumentException(type + " Body 必须有 value");
        }
    }

    public static JmeterBody none() {
        return new JmeterBody("NONE", null);
    }

    public static JmeterBody json(JsonNode value) {
        return new JmeterBody("JSON", value);
    }
}
