package com.autotest.contracts.extraction;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Set;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/** 平台、试算和 Runner 共用的声明式断言合同。 */
public record AssertionRule(int ruleIndex, String type, String operator, String expression, JsonNode expected) {

    private static final Set<String> TYPES = Set.of("STATUS", "BODY", "JSON_PATH", "JMES_PATH", "XPATH",
            "HEADER", "COOKIE", "SCHEMA", "RESPONSE_TIME", "VARIABLE");
    private static final Set<String> EXISTENCE = Set.of("EXISTS", "NOT_EXISTS");
    private static final Set<String> JSON_OPERATORS = Set.of("EXISTS", "NOT_EXISTS", "EQUALS", "NOT_EQUALS",
            "GREATER_THAN", "LESS_THAN", "CONTAINS");
    private static final Set<String> TEXT_OPERATORS = Set.of("EQUALS", "CONTAINS", "NOT_CONTAINS", "MATCHES");
    private static final Set<String> VARIABLE_OPERATORS = Set.of("EQUALS", "NOT_EQUALS", "GREATER_THAN",
            "LESS_THAN", "CONTAINS");
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    public AssertionRule {
        if (ruleIndex < 0) throw new IllegalArgumentException("断言序号不能为负数");
        type = required(type, "断言类型").toUpperCase(Locale.ROOT);
        operator = required(operator, "断言操作符").toUpperCase(Locale.ROOT);
        expression = expression == null ? "" : expression;
        if (!TYPES.contains(type)) throw new IllegalArgumentException("断言类型不支持: " + type);
        switch (type) {
            case "STATUS" -> {
                requireOperator(operator, Set.of("EQUALS"));
                requireExpected(expected, type, operator);
                if (!expected.isIntegralNumber() || expected.asInt() < 100 || expected.asInt() > 599) {
                    throw new IllegalArgumentException("STATUS expected 必须是 100 到 599");
                }
            }
            case "BODY" -> {
                requireOperator(operator, TEXT_OPERATORS);
                requireExpected(expected, type, operator);
                if ("MATCHES".equals(operator)) requireTextExpected(expected, type, operator);
                if ("MATCHES".equals(operator)) SafeRegex.compile(expected.textValue());
            }
            case "JSON_PATH", "JMES_PATH" -> {
                requireExpression(expression, type);
                requireOperator(operator, JSON_OPERATORS);
                requireExistenceExpected(operator, expected, type);
                if (Set.of("GREATER_THAN", "LESS_THAN").contains(operator)) {
                    requireNumericExpected(expected, type, operator);
                }
            }
            case "XPATH" -> {
                requireExpression(expression, type);
                requireOperator(operator, EXISTENCE);
                if (expected != null) throw new IllegalArgumentException(type + " 存在性断言不得包含 expected");
            }
            case "HEADER", "COOKIE" -> {
                requireExpression(expression, type);
                requireOperator(operator, Set.of("EXISTS", "NOT_EXISTS", "EQUALS", "CONTAINS",
                        "NOT_CONTAINS", "MATCHES"));
                requireExistenceExpected(operator, expected, type);
                if ("MATCHES".equals(operator)) {
                    requireTextExpected(expected, type, operator);
                    SafeRegex.compile(expected.textValue());
                }
            }
            case "SCHEMA" -> {
                requireOperator(operator, Set.of("VALIDATE"));
                if (expected == null || !expected.isObject()) throw new IllegalArgumentException("SCHEMA expected 必须是对象");
                if (hasRemoteReference(expected)) throw new IllegalArgumentException("JSON Schema 不允许远程 $ref");
            }
            case "RESPONSE_TIME" -> {
                requireOperator(operator, Set.of("LESS_THAN"));
                if (expected == null || !expected.isNumber() || expected.asDouble() < 0) {
                    throw new IllegalArgumentException("RESPONSE_TIME expected 必须是非负数");
                }
            }
            case "VARIABLE" -> {
                requireExpression(expression, type);
                if (!VARIABLE.matcher(expression).matches() || expression.startsWith("secret:")) {
                    throw new IllegalArgumentException("变量名不合法");
                }
                requireOperator(operator, VARIABLE_OPERATORS);
                requireExpected(expected, type, operator);
                if (Set.of("GREATER_THAN", "LESS_THAN").contains(operator)) {
                    requireNumericExpected(expected, type, operator);
                }
            }
            default -> throw new IllegalArgumentException("断言类型不支持: " + type);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
        return value;
    }

    private static void requireExpression(String expression, String type) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException(type + " expression 不能为空");
        if (expression.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(type + " expression 不得包含控制字符");
        }
        if ("JSON_PATH".equals(type) && !expression.startsWith("$")) {
            throw new IllegalArgumentException("JSONPath 必须以 $ 开头");
        }
    }

    private static void requireOperator(String operator, Set<String> supported) {
        if (!supported.contains(operator)) throw new IllegalArgumentException("断言操作符不支持: " + operator);
    }

    private static void requireExpected(JsonNode expected, String type, String operator) {
        if (expected == null) throw new IllegalArgumentException(type + " " + operator + " 必须包含 expected");
    }

    private static void requireTextExpected(JsonNode expected, String type, String operator) {
        if (expected == null || !expected.isTextual()) {
            throw new IllegalArgumentException(type + " " + operator + " expected 必须是字符串");
        }
    }

    private static void requireNumericExpected(JsonNode expected, String type, String operator) {
        if (expected == null || !expected.isNumber()) {
            throw new IllegalArgumentException(type + " " + operator + " expected 必须是数字");
        }
    }

    private static void requireExistenceExpected(String operator, JsonNode expected, String type) {
        if (EXISTENCE.contains(operator)) {
            if (expected != null) throw new IllegalArgumentException(operator + " 不得包含 expected");
        } else {
            requireExpected(expected, type, operator);
        }
    }

    private static boolean hasRemoteReference(JsonNode node) {
        if (node == null) return false;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if ("$ref".equals(field.getKey())
                        && (!field.getValue().isTextual() || isRemoteReference(field.getValue().textValue()))) return true;
                if (hasRemoteReference(field.getValue())) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) if (hasRemoteReference(item)) return true;
        }
        return false;
    }

    private static boolean isRemoteReference(String reference) {
        String value = reference == null ? "" : reference.strip();
        return value.startsWith("//") || value.matches("[A-Za-z][A-Za-z0-9+.-]*:.*");
    }
}
