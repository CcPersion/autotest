package com.autotest.contracts.extraction;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 平台和 Runner 共用的 HTTP 响应提取规则。 */
public record ExtractionRule(String type, String expression, String variable,
                             JsonNode defaultValue, boolean failIfMissing) {

    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final Set<String> TYPES = Set.of("JSON_PATH", "JMESPATH", "XPATH", "REGEX", "HEADER", "COOKIE");

    public ExtractionRule {
        type = required(type, "提取器类型").toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的提取器类型: " + type);
        }
        expression = required(expression, "提取表达式");
        variable = required(variable, "提取变量名");
        if (!VARIABLE.matcher(variable).matches() || variable.startsWith("secret:")) {
            throw new IllegalArgumentException("提取变量名不合法");
        }
    }

    public ExtractionRule(String type, String expression, String variable, JsonNode defaultValue) {
        this(type, expression, variable, defaultValue, true);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
        return value;
    }
}
