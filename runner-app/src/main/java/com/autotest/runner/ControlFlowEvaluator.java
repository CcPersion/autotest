package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 只处理平台白名单控制流，不执行表达式语言或脚本。 */
final class ControlFlowEvaluator {
    private static final int MAX_ITERATIONS = 1000;

    private ControlFlowEvaluator() {
    }

    static boolean condition(JsonNode plan, Map<String, JsonNode> extracted) {
        return condition(plan, context(plan, extracted));
    }

    static boolean condition(JsonNode plan, RunVariableContext context) {
        requireDefined(plan, context, "controlFlow");
        String operator = plan.path("operator").asText("").toUpperCase(Locale.ROOT);
        JsonNode left = resolve(plan, plan.path("left").asText(""), context);
        boolean exists = left != null && !left.isMissingNode() && !left.isNull();
        if ("EXISTS".equals(operator)) return exists;
        if ("NOT_EXISTS".equals(operator)) return !exists;
        String leftText = scalar(left);
        String rightText = scalar(context.resolveNode(plan.get("right")));
        return switch (operator) {
            case "EQUALS" -> leftText.equals(rightText);
            case "NOT_EQUALS" -> !leftText.equals(rightText);
            case "CONTAINS" -> leftText.contains(rightText);
            case "NOT_CONTAINS" -> !leftText.contains(rightText);
            case "GREATER_THAN" -> compare(leftText, rightText) > 0;
            case "LESS_THAN" -> compare(leftText, rightText) < 0;
            default -> throw new IllegalArgumentException("条件运算符不支持: " + operator);
        };
    }

    static List<JsonNode> listItems(JsonNode plan, Map<String, JsonNode> extracted) {
        return listItems(plan, context(plan, extracted));
    }

    static List<JsonNode> listItems(JsonNode plan, RunVariableContext context) {
        requireDefined(plan, context, "controlFlow.items");
        JsonNode configured = plan.get("items");
        JsonNode resolved = configured != null && configured.isTextual()
                ? resolve(plan, configured.asText(), context) : configured;
        if (resolved != null && resolved.isArray()) {
            List<JsonNode> values = new ArrayList<>();
            resolved.forEach(item -> values.add(item == null ? JsonNodeFactory.instance.nullNode() : item.deepCopy()));
            return values;
        }
        String text = scalar(resolved);
        if (text.isBlank()) return List.of();
        List<JsonNode> values = new ArrayList<>();
        for (String item : text.split(",", -1)) values.add(JsonNodeFactory.instance.textNode(item.strip()));
        return values;
    }

    static JsonNode resolve(JsonNode plan, String expression, Map<String, JsonNode> extracted) {
        return resolve(plan, expression, context(plan, extracted));
    }

    static JsonNode resolve(JsonNode plan, String expression, RunVariableContext context) {
        String value = expression == null ? "" : expression.strip();
        RunVariableContext.PreflightResult preflight = context.preflight(TextNode.valueOf(value), "controlFlow");
        if (!preflight.valid()) throw new IllegalArgumentException(preflight.code() + ": " + preflight.path());
        return context.resolveNode(TextNode.valueOf(value));
    }

    private static RunVariableContext context(JsonNode plan, Map<String, JsonNode> extracted) {
        RunVariableContext context = RunVariableContext.fromPlan("control-flow", plan == null
                ? JsonNodeFactory.instance.objectNode() : plan);
        if (extracted != null) context.putExtractedAll(extracted);
        return context;
    }

    private static void requireDefined(JsonNode plan, RunVariableContext context, String path) {
        RunVariableContext.PreflightResult preflight = context.preflight(plan, path);
        if (!preflight.valid()) throw new IllegalArgumentException(preflight.code() + ": " + preflight.path());
    }

    static int maxIterations(JsonNode plan) {
        int value = plan.path("maxIterations").asInt(100);
        if (value < 1 || value > MAX_ITERATIONS) throw new IllegalArgumentException("最大循环次数必须在 1 到 1000 之间");
        return value;
    }

    static String scalar(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        return value.isTextual() ? value.textValue() : value.toString();
    }

    private static int compare(String left, String right) {
        try {
            return new BigDecimal(left.strip()).compareTo(new BigDecimal(right.strip()));
        } catch (NumberFormatException ignored) {
            return left.compareTo(right);
        }
    }
}
