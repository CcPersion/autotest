package com.autotest.runner;

import com.autotest.contracts.extraction.AssertionRule;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/** 平台单接口断言；仅映射 JMeter 原生断言，不执行用户脚本。 */
public record JmeterAssertion(String type, String operator, String expression, JsonNode expected) {

    public JmeterAssertion {
        type = required(type, "断言类型");
        operator = required(operator, "断言操作符");
        expression = expression == null ? "" : expression;
        // Runner 与平台/试算共用同一断言合同，避免 JMeter 侧悄悄接受
        // 平台无法持久化或执行的操作符、目标和 expected 类型。
        String contractExpression = legacyHeaderCookieTarget(type, operator, expression, expected)
                ? "__legacy_header_cookie_target__" : expression;
        JsonNode contractExpected = existenceWithLegacyFlag(operator) ? null : expected;
        new AssertionRule(0, type, operator, contractExpression, contractExpected);
    }

    private static boolean legacyHeaderCookieTarget(String type, String operator,
                                                     String expression, JsonNode expected) {
        return ("HEADER".equals(type) || "COOKIE".equals(type))
                && (expression == null || expression.isBlank())
                && expected != null
                && !"EXISTS".equals(operator)
                && !"NOT_EXISTS".equals(operator);
    }

    private static boolean existenceWithLegacyFlag(String operator) {
        return ("EXISTS".equals(operator) || "NOT_EXISTS".equals(operator));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.toUpperCase(Locale.ROOT);
    }

}
