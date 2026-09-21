package com.autotest.runner;

import java.util.Objects;
import java.util.Locale;

/** 单步响应提取器；仅映射 JMeter 白名单 PostProcessor，不执行用户脚本。 */
public record JmeterExtractor(String type, String expression, String variable, String defaultValue,
                              boolean failIfMissing, boolean defaultConfigured) {

    public JmeterExtractor(String type, String expression, String variable, String defaultValue) {
        this(type, expression, variable, defaultValue, true, true);
    }

    public JmeterExtractor(String type, String expression, String variable, String defaultValue,
                           boolean failIfMissing) {
        this(type, expression, variable, defaultValue, failIfMissing, true);
    }

    public JmeterExtractor {
        type = Objects.requireNonNull(type, "提取器类型不能为空").toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("JSON_PATH", "JMESPATH", "XPATH", "REGEX", "HEADER", "COOKIE")
                .contains(type)) {
            throw new IllegalArgumentException("不支持的提取器类型: " + type);
        }
        expression = required(expression, "提取表达式不能为空");
        variable = required(variable, "提取变量名不能为空");
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }
}
