package com.autotest.contracts.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 对应 F2-03 的 HTTP/变量声明式断言求值。 */
public final class ControlledAssertionEvaluator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ControlledAssertionEvaluator() {
    }

    public static ControlledAssertionResult evaluate(AssertionRule rule, HttpResponseSample response,
                                                     Map<String, JsonNode> variables) {
        try {
            JsonNode actual = actual(rule, response, variables == null ? Map.of() : variables);
            boolean passed = switch (rule.operator()) {
                case "EXISTS" -> actual != null;
                case "NOT_EXISTS" -> actual == null;
                case "EQUALS" -> actual != null && actual.equals(rule.expected());
                case "NOT_EQUALS" -> actual == null || !actual.equals(rule.expected());
                case "CONTAINS" -> contains(actual, rule.expected());
                case "NOT_CONTAINS" -> !contains(actual, rule.expected());
                case "MATCHES" -> actual != null && matches(rule.expected().asText(), actual.asText());
                case "GREATER_THAN" -> compareNumbers(actual, rule.expected()) > 0;
                case "LESS_THAN" -> compareNumbers(actual, rule.expected()) < 0;
                case "VALIDATE" -> validateSchema(rule.expected(), actual);
                default -> throw new IllegalArgumentException("断言操作符不支持");
            };
            return new ControlledAssertionResult(rule.ruleIndex(), rule.type(), rule.operator(), rule.expression(),
                    rule.expected(), actual, passed, "", passed ? "断言通过" : "断言失败");
        } catch (PatternSyntaxException exception) {
            return new ControlledAssertionResult(rule.ruleIndex(), rule.type(), rule.operator(), rule.expression(),
                    rule.expected(), null, false, "INVALID_EXPRESSION", "正则表达式不合法");
        } catch (IllegalArgumentException exception) {
            return new ControlledAssertionResult(rule.ruleIndex(), rule.type(), rule.operator(), rule.expression(),
                    rule.expected(), null, false, "INVALID_EXPRESSION", exception.getMessage());
        } catch (StackOverflowError error) {
            return new ControlledAssertionResult(rule.ruleIndex(), rule.type(), rule.operator(), rule.expression(),
                    rule.expected(), null, false, "INVALID_EXPRESSION", "正则表达式求值超出受控限制");
        }
    }

    private static boolean matches(String expression, String actual) {
        SafeRegex.ensureInputLength(actual);
        try {
            return SafeRegex.compile(expression).matcher(actual).find();
        } catch (StackOverflowError error) {
            throw new IllegalArgumentException("正则表达式求值超出受控限制");
        }
    }

    private static JsonNode actual(AssertionRule rule, HttpResponseSample response, Map<String, JsonNode> variables) {
        return switch (rule.type()) {
            case "STATUS" -> IntNode.valueOf(response.status());
            case "RESPONSE_TIME" -> LongNode.valueOf(response.durationMs());
            case "BODY" -> TextNode.valueOf(response.bodyText());
            case "HEADER" -> ControlledExtractionEvaluator.evaluate("HEADER", rule.expression(), response);
            case "COOKIE" -> ControlledExtractionEvaluator.evaluate("COOKIE", rule.expression(), response);
            case "JSON_PATH" -> ControlledExtractionEvaluator.evaluate("JSON_PATH", rule.expression(), response);
            case "JMES_PATH" -> ControlledExtractionEvaluator.evaluate("JMES_PATH", rule.expression(), response);
            case "XPATH" -> ControlledExtractionEvaluator.evaluate("XPATH", rule.expression(), response);
            case "VARIABLE" -> variables.get(rule.expression());
            case "SCHEMA" -> responseBodyJson(response.bodyText());
            default -> throw new IllegalArgumentException("断言类型不支持");
        };
    }

    private static JsonNode responseBodyJson(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception exception) {
            throw new IllegalArgumentException("响应体不是合法 JSON");
        }
    }

    private static boolean contains(JsonNode actual, JsonNode expected) {
        if (actual == null || expected == null) return false;
        if (actual.isArray()) {
            for (JsonNode item : actual) if (item.equals(expected)) return true;
            return false;
        }
        if (actual.isObject() && expected.isTextual()) return actual.has(expected.textValue());
        return actual.asText().contains(expected.asText());
    }

    private static int compareNumbers(JsonNode actual, JsonNode expected) {
        if (actual == null || expected == null || !actual.isNumber() || !expected.isNumber()) {
            throw new IllegalArgumentException("大于/小于只支持十进制数值");
        }
        return new BigDecimal(actual.asText()).compareTo(new BigDecimal(expected.asText()));
    }

    private static boolean validateSchema(JsonNode schema, JsonNode actual) {
        if (schema == null || actual == null) return false;
        JsonNode type = schema.get("type");
        if (type != null && type.isTextual()) {
            boolean matches = switch (type.textValue()) {
                case "object" -> actual.isObject();
                case "array" -> actual.isArray();
                case "string" -> actual.isTextual();
                case "number" -> actual.isNumber();
                case "integer" -> actual.isIntegralNumber();
                case "boolean" -> actual.isBoolean();
                case "null" -> actual.isNull();
                default -> false;
            };
            if (!matches) return false;
        }
        JsonNode required = schema.get("required");
        if (required != null && required.isArray() && actual.isObject()) {
            for (JsonNode name : required) if (name.isTextual() && !actual.has(name.textValue())) return false;
        }
        return true;
    }
}
