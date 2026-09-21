package com.autotest.platform.run;

import com.autotest.contracts.util.BuiltinFunctionContract;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 平台入队前的无副作用变量预检；规则与 Runner 的运行上下文保持同一优先级语义。 */
final class RunPlanVariablePreflight {

    private static final Pattern TOKEN = Pattern.compile("(\\$\\{([^{}]+)}|\\{\\{\\$([^{}]+)}})");
    private static final Pattern SECRET = Pattern.compile("secret:[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    private RunPlanVariablePreflight() {
    }

    static void validate(JsonNode plan) {
        if (plan == null || !plan.isObject()) {
            throw new ValidationException("VARIABLE_INVALID", "executionPlan");
        }
        // 直接 API_CASE 的每条启用数据行都是一个独立上下文，不能把多行字段名
        // 合并后再预检，否则会把某一行的变量错误地借给另一行。
        if (!plan.has("scenarioSteps") && !plan.has("suiteSteps")) {
            validatePlanWithRows(plan, Set.of(), Set.of(), "executionPlan", false);
        } else {
            Set<String> names = declaredNames(plan);
            validateNode(plan, names, Set.of(), "executionPlan");
        }
    }

    private static void validateNode(JsonNode node, Set<String> names, Set<String> produced, String path) {
        validateNode(node, names, produced, path, false);
    }

    private static void validateNode(JsonNode node, Set<String> names, Set<String> produced, String path,
                                     boolean skipCurrentDataRows) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            Matcher matcher = TOKEN.matcher(node.textValue());
            while (matcher.find()) {
                if (matcher.group(2) != null) {
                    String variable = matcher.group(2);
                    if (!SECRET.matcher(variable).matches()
                            && !names.contains(variable) && !produced.contains(variable)) {
                        throw new ValidationException("VARIABLE_UNDEFINED", path);
                    }
                } else {
                    validateBuiltin(matcher.group(3), path);
                }
            }
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (skipCurrentDataRows && field.getKey().equals("dataRows")) {
                    continue;
                }
                if (field.getKey().equals("scenarioSteps") && field.getValue().isArray()) {
                    validateSteps(field.getValue(), names, produced, path + ".scenarioSteps");
                } else if (field.getKey().equals("suiteSteps") && field.getValue().isArray()) {
                    // 集合成员是独立执行单元：根计划变量和前一成员提取值都不能
                    // 流入成员，成员自己的 plan/variableScopes 由下面重新建立。
                    validateSuiteSteps(field.getValue(), path + ".suiteSteps");
                } else if (field.getKey().equals("scenarioSteps") || field.getKey().equals("suiteSteps")) {
                    throw new ValidationException("VARIABLE_INVALID", path + "." + field.getKey());
                } else {
                    validateNode(field.getValue(), names, produced, path + "." + field.getKey(), false);
                }
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                validateNode(node.get(index), names, produced, path + "[" + index + "]", false);
            }
        }
    }

    private static void validateSteps(JsonNode rawSteps, Set<String> inheritedNames,
                                      Set<String> inheritedProduced, String path) {
        List<JsonNode> steps = new ArrayList<>();
        rawSteps.forEach(step -> {
            if (step != null && step.isObject() && step.path("enabled").asBoolean(true)) steps.add(step);
        });
        steps.sort(Comparator.comparingInt((JsonNode step) -> step.path("position").asInt(Integer.MAX_VALUE))
                .thenComparing((JsonNode step) -> step.path("stepId").asText("")));
        Set<String> produced = new LinkedHashSet<>(inheritedProduced);
        for (JsonNode step : steps) {
            JsonNode plan = step.get("plan");
            if (plan == null || plan.isNull()) plan = step;
            if (!plan.isObject()) throw new ValidationException("VARIABLE_INVALID", path);
            String stepPath = path + "." + step.path("stepId").asText("step") + ".plan";
            validatePlanWithRows(plan, inheritedNames, produced, stepPath, true);
            if (guaranteedProducer(step)) produced.addAll(extractorNames(plan));
        }
    }

    /**
     * 对一个可执行 plan 建立自己的作用域，并按数据行分别预检。
     * scenario=true 时只允许至多一条启用数据行；场景不做隐式多行展开。
     */
    private static void validatePlanWithRows(JsonNode plan, Set<String> inheritedNames,
                                             Set<String> inheritedProduced, String path,
                                             boolean scenario) {
        if (plan == null || !plan.isObject()) {
            throw new ValidationException("VARIABLE_INVALID", path);
        }
        Set<String> baseNames = new LinkedHashSet<>(inheritedNames);
        baseNames.addAll(declaredNames(plan));
        List<DataRowRef> rows = enabledDataRows(plan, scenario, path);
        if (rows.isEmpty()) {
            validateNode(plan, baseNames, inheritedProduced, path, true);
            return;
        }
        for (DataRowRef row : rows) {
            Set<String> rowNames = new LinkedHashSet<>(baseNames);
            addObjectKeys(rowNames, row.node().get("values"));
            validateNode(plan, rowNames, inheritedProduced, path, true);
            JsonNode values = row.node().get("values");
            if (values != null && !values.isNull()) {
                validateNode(values, rowNames, inheritedProduced,
                        path + ".dataRows[" + row.index() + "].values");
            }
        }
    }

    /** 集合成员不能共享根计划和前一成员的作用域；禁用成员完全跳过。 */
    private static void validateSuiteSteps(JsonNode rawSteps, String path) {
        if (!rawSteps.isArray()) {
            throw new ValidationException("VARIABLE_INVALID", path);
        }
        List<JsonNode> members = new ArrayList<>();
        rawSteps.forEach(member -> {
            if (member != null && member.isObject() && member.path("enabled").asBoolean(true)) {
                members.add(member);
            }
        });
        members.sort(Comparator.comparingInt((JsonNode member) -> member.path("position").asInt(Integer.MAX_VALUE))
                .thenComparing(member -> member.path("memberId").asText("")));
        for (JsonNode member : members) {
            JsonNode plan = member.get("plan");
            if (plan == null || plan.isNull()) plan = member;
            if (!plan.isObject()) {
                throw new ValidationException("VARIABLE_INVALID", path + "." + member.path("memberId").asText("member"));
            }
            String memberPath = path + "." + member.path("memberId").asText("member") + ".plan";
            // Set.of() is intentional: suite root variables and sibling producer
            // outputs must never satisfy this member's references.
            validatePlanWithRows(plan, Set.of(), Set.of(), memberPath, false);
        }
    }

    private static boolean guaranteedProducer(JsonNode step) {
        String section = step.path("section").asText("MAIN").toUpperCase(Locale.ROOT);
        String kind = step.path("kind").asText("").toUpperCase(Locale.ROOT);
        String parentId = step.path("parentId").asText("");
        String strategy = step.path("failureStrategy").asText("STOP").toUpperCase(Locale.ROOT);
        return section.equals("MAIN") && parentId.isBlank()
                && !kind.equals("CONDITION") && !kind.equals("LOOP") && strategy.equals("STOP");
    }

    private static Set<String> extractorNames(JsonNode plan) {
        Set<String> names = new LinkedHashSet<>();
        JsonNode extractors = plan.get("extractors");
        if (extractors != null && extractors.isArray()) {
            extractors.forEach(item -> {
                String name = item.path("variable").asText("").strip();
                if (!name.isBlank()) names.add(name);
            });
        }
        return names;
    }

    private static Set<String> declaredNames(JsonNode plan) {
        Set<String> names = new LinkedHashSet<>();
        addObjectKeys(names, plan.get("variables"));
        addObjectKeys(names, plan.get("scenarioVariables"));
        JsonNode scopes = plan.get("variableScopes");
        if (scopes != null && scopes.isObject()) {
            scopes.fields().forEachRemaining(entry -> addObjectKeys(names, entry.getValue()));
        }
        return names;
    }

    private static void addObjectKeys(Set<String> names, JsonNode value) {
        if (value != null && value.isObject()) value.fieldNames().forEachRemaining(names::add);
    }

    private static List<DataRowRef> enabledDataRows(JsonNode plan, boolean scenario, String path) {
        List<DataRowRef> enabledRows = new ArrayList<>();
        JsonNode dataRows = plan.get("dataRows");
        if (dataRows == null || dataRows.isNull() || dataRows.isMissingNode()) return enabledRows;
        if (!dataRows.isArray()) throw new ValidationException("VARIABLE_INVALID", path + ".dataRows");
        for (int index = 0; index < dataRows.size(); index++) {
            JsonNode row = dataRows.get(index);
            if (row != null && row.path("enabled").asBoolean(true)) {
                enabledRows.add(new DataRowRef(index, row));
                if (scenario && enabledRows.size() > 1) {
                    throw new ValidationException("DATA_ROWS_IN_SCENARIO_UNSUPPORTED", path);
                }
            }
        }
        return enabledRows;
    }

    private static void validateBuiltin(String expression, String path) {
        if (expression == null || expression.isBlank()) throw new ValidationException("VARIABLE_INVALID", path);
        String[] parts = expression.split(":", 2);
        switch (parts[0]) {
            case "uuid", "timestamp", "date" -> {
                if (parts.length > 1) throw new ValidationException("VARIABLE_INVALID", path);
            }
            case "formatdate" -> {
                String pattern = parts.length == 1 ? "yyyy-MM-dd" : parts[1];
                try {
                    BuiltinFunctionContract.validateFormatDatePattern(pattern);
                } catch (IllegalArgumentException exception) {
                    throw new ValidationException("VARIABLE_INVALID", path);
                }
            }
            case "randomint" -> {
                String range = parts.length == 1 ? "0,999999" : parts[1];
                String[] bounds = range.split(",", -1);
                try {
                    if (bounds.length != 2) throw new NumberFormatException();
                    long min = Long.parseLong(bounds[0].strip());
                    long max = Long.parseLong(bounds[1].strip());
                    if (min < 0 || max > Integer.MAX_VALUE || min > max) {
                        throw new NumberFormatException();
                    }
                } catch (RuntimeException exception) {
                    throw new ValidationException("VARIABLE_INVALID", path);
                }
            }
            case "randomstring" -> {
                try {
                    int length = Integer.parseInt((parts.length == 1 ? "8" : parts[1]).strip());
                    if (length < 1 || length > 256) throw new NumberFormatException();
                } catch (RuntimeException exception) {
                    throw new ValidationException("VARIABLE_INVALID", path);
                }
            }
            default -> throw new ValidationException("VARIABLE_INVALID", path);
        }
    }

    static final class ValidationException extends IllegalArgumentException {
        private final String code;
        private final String path;

        ValidationException(String code, String path) {
            super(code + ": " + path);
            this.code = code;
            this.path = path;
        }

        String code() { return code; }
        String path() { return path; }
    }

    private record DataRowRef(int index, JsonNode node) {
    }
}
