package com.autotest.platform.scenario;

import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

/** 校验无脚本控制流配置，保证平台与 Runner 使用同一份有限契约。 */
final class ControlFlowStepValidator {
    private static final Set<String> OPERATORS = Set.of(
            "EQUALS", "NOT_EQUALS", "CONTAINS", "NOT_CONTAINS",
            "GREATER_THAN", "LESS_THAN", "EXISTS", "NOT_EXISTS");
    private static final Set<String> LOOP_MODES = Set.of("FIXED", "LIST", "WHILE");
    private static final int MAX_ITERATIONS = 1000;
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]*)}");
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    private ControlFlowStepValidator() {
    }

    static void validateCondition(JsonNode config, String path) {
        object(config, path);
        String left = requiredText(config, "left", path + ".left");
        safeTemplate(left, path + ".left");
        String operator = upper(config.path("operator").asText(""));
        if (!OPERATORS.contains(operator)) invalid(path + ".operator", "条件运算符不支持");
        if (!Set.of("EXISTS", "NOT_EXISTS").contains(operator)) {
            requiredScalar(config.get("right"), path + ".right");
            if (config.get("right").isTextual()) safeTemplate(config.get("right").asText(), path + ".right");
        }
        String branch = upper(config.path("branch").asText("THEN"));
        if (!Set.of("THEN", "ELSE").contains(branch)) invalid(path + ".branch", "条件分支只能是 THEN 或 ELSE");
    }

    static void validateLoop(JsonNode config, String path) {
        object(config, path);
        String mode = upper(config.path("mode").asText(""));
        if (!LOOP_MODES.contains(mode)) invalid(path + ".mode", "循环模式只能是 FIXED、LIST 或 WHILE");
        int max = positiveInt(config, "maxIterations", 100, path);
        if (max > MAX_ITERATIONS) invalid(path + ".maxIterations", "最大循环次数不能超过 1000");
        if ("FIXED".equals(mode)) {
            int count = positiveInt(config, "count", -1, path);
            if (count > max) invalid(path + ".count", "固定循环次数不能超过最大循环次数");
        } else if ("LIST".equals(mode)) {
            String items = requiredText(config, "items", path + ".items");
            safeTemplate(items, path + ".items");
            String itemVariable = requiredText(config, "itemVariable", path + ".itemVariable");
            if (!VARIABLE_NAME.matcher(itemVariable).matches()) invalid(path + ".itemVariable", "变量名只能包含字母、数字、点、下划线和短横线");
        } else {
            validateCondition(config, path);
        }
    }

    private static void object(JsonNode config, String path) {
        if (config == null || !config.isObject()) invalid(path, "控制流配置必须是 JSON 对象");
    }

    private static String requiredText(JsonNode config, String field, String path) {
        String value = config.path(field).asText("").strip();
        if (value.isBlank() || value.length() > 2048) invalid(path, "字段不能为空且不能超过 2048 个字符");
        return value;
    }

    private static void requiredScalar(JsonNode value, String path) {
        if (value == null || value.isNull() || !value.isValueNode()) invalid(path, "条件比较值必须是标量");
    }

    private static void safeTemplate(String value, String path) {
        Matcher matcher = VARIABLE.matcher(value);
        while (matcher.find()) {
            if (!VARIABLE_NAME.matcher(matcher.group(1)).matches()) {
                invalid(path, "控制流只允许引用普通变量，不允许 JMeter 函数或脚本");
            }
        }
        if (VARIABLE.matcher(value).replaceAll("").contains("${")) {
            invalid(path, "变量表达式格式不正确");
        }
    }

    private static int positiveInt(JsonNode config, String field, int fallback, String path) {
        JsonNode value = config.get(field);
        if (value == null && fallback >= 0) return fallback;
        if (value == null || !value.canConvertToInt() || value.asInt() < 1) invalid(path + "." + field, "必须是正整数");
        return value.asInt();
    }

    private static String upper(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static void invalid(String field, String message) {
        throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "INVALID_CONTROL_FLOW", message,
                java.util.Map.of("field", field));
    }
}
