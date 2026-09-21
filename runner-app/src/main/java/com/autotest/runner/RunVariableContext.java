package com.autotest.runner;

import com.autotest.contracts.util.BuiltinFunctionContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个运行内的变量上下文。基础作用域不可变，提取值只属于这个上下文，
 * loop overlay 通过可关闭句柄临时覆盖，避免不同数据行或不同运行互相污染。
 *
 * <p>密钥值不属于该类的输入；${secret:name} 只保持不透明引用，实际值由
 * SecretFileMaterializer 在受控边界解析。</p>
 */
public final class RunVariableContext {

    private static final Pattern TOKEN = Pattern.compile("(\\$\\{([^{}]+)}|\\{\\{\\$([^{}]+)}})");
    private static final Pattern WHOLE_VARIABLE = Pattern.compile("^\\$\\{([^{}]+)}$");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final Pattern SECRET = Pattern.compile("secret:([A-Za-z0-9][A-Za-z0-9._-]*)");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final String runId;
    private final String rowId;
    private final Map<String, JsonNode> environment;
    private final Map<String, JsonNode> scenario;
    private final Map<String, JsonNode> caseVariables;
    private final Map<String, JsonNode> dataRow;
    private final ConcurrentMap<String, JsonNode> extracted;
    private final Object overlaysLock = new Object();
    private final Deque<Map<String, JsonNode>> overlays = new ArrayDeque<>();

    private RunVariableContext(String runId, String rowId,
                               Map<String, JsonNode> environment,
                               Map<String, JsonNode> scenario,
                               Map<String, JsonNode> caseVariables,
                               Map<String, JsonNode> dataRow,
                               Map<String, JsonNode> extracted) {
        this(runId, rowId, environment, scenario, caseVariables, dataRow,
                extracted == null ? null : new ConcurrentHashMap<>(extracted), false);
    }

    private RunVariableContext(String runId, String rowId,
                               Map<String, JsonNode> environment,
                               Map<String, JsonNode> scenario,
                               Map<String, JsonNode> caseVariables,
                               Map<String, JsonNode> dataRow,
                               ConcurrentMap<String, JsonNode> extracted,
                               boolean shareExtracted) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId 不能为空");
        this.runId = runId;
        this.rowId = rowId == null || rowId.isBlank() ? null : rowId;
        this.environment = immutableScope(environment, "environment");
        this.scenario = immutableScope(scenario, "scenario");
        this.caseVariables = immutableScope(caseVariables, "caseVariables");
        this.dataRow = immutableScope(dataRow, "dataRow");
        this.extracted = shareExtracted ? extracted : new ConcurrentHashMap<>();
        if (!shareExtracted && extracted != null) extracted.forEach((name, value) -> this.extracted.put(
                validateWritableName(name), copy(value, "extracted." + name)));
    }

    public static Builder builder(String runId) {
        return new Builder(runId, null);
    }

    public static Builder builder(String runId, String rowId) {
        return new Builder(runId, rowId);
    }

    /** 从保存的执行计划建立上下文；旧 variables 字段按 caseVariables 兼容。 */
    public static RunVariableContext fromPlan(String runId, JsonNode plan) {
        if (plan == null || !plan.isObject()) throw new IllegalArgumentException("执行计划必须是对象");
        JsonNode scopes = plan.get("variableScopes");
        if (scopes == null || scopes.isNull()) scopes = JsonNodeFactory.instance.objectNode();
        if (!scopes.isObject()) throw new IllegalArgumentException("variableScopes 必须是对象");
        Map<String, JsonNode> caseValues = new LinkedHashMap<>(scope(plan, "variables"));
        // F1 旧计划使用 variables；新计划使用 caseVariables。新字段同名值优先。
        caseValues.putAll(scope(scopes, "caseVariables"));
        Map<String, JsonNode> scenarioValues = new LinkedHashMap<>(scope(scopes, "scenario"));
        if (scenarioValues.isEmpty()) scenarioValues.putAll(scope(plan, "scenarioVariables"));
        return new RunVariableContext(runId, null,
                scope(scopes, "environment"), scenarioValues, caseValues,
                scope(scopes, "dataRow"), scope(scopes, "extracted"));
    }

    /** 从 serializeScopes 结果恢复普通作用域，不恢复 loop 临时覆盖。 */
    public static RunVariableContext fromScopes(String runId, JsonNode serialized) {
        if (serialized == null || !serialized.isObject()) throw new IllegalArgumentException("变量作用域必须是对象");
        return new RunVariableContext(runId, null,
                scope(serialized, "environment"), scope(serialized, "scenario"),
                scope(serialized, "caseVariables"), scope(serialized, "dataRow"),
                scope(serialized, "extracted"));
    }

    public String runId() {
        return runId;
    }

    public String rowId() {
        return rowId;
    }

    public void putExtracted(String name, JsonNode value) {
        extracted.put(validateWritableName(name), copy(value, "extracted." + name));
    }

    public void putExtractedAll(Map<String, JsonNode> values) {
        if (values != null) values.forEach(this::putExtracted);
    }

    public void clearExtracted() {
        extracted.clear();
    }

    public RunVariableContext withExtractedCleared() {
        return new RunVariableContext(runId, rowId, environment, scenario, caseVariables, dataRow, Map.of());
    }

    /** 为直接 API_CASE 建立独立数据行上下文；提取值从该行开始为空。 */
    public RunVariableContext forRow(String newRowId, Map<String, JsonNode> values) {
        return new RunVariableContext(runId, newRowId, environment, scenario, caseVariables, values, Map.of());
    }

    /** 为场景中的单数据行切换 row 层，但保留同一 run 的 extracted。 */
    public RunVariableContext withDataRow(String newRowId, Map<String, JsonNode> values) {
        RunVariableContext result = new RunVariableContext(runId, newRowId, environment, scenario,
                caseVariables, values, extracted, true);
        copyOverlaysTo(result);
        return result;
    }

    /** 为场景中的当前步骤切换基础作用域，同时共享同一个 run 的 extracted map。 */
    public RunVariableContext forPlan(JsonNode plan) {
        RunVariableContext base = fromPlan(runId, plan);
        RunVariableContext result = new RunVariableContext(runId, rowId, base.environment, base.scenario,
                base.caseVariables, base.dataRow, extracted, true);
        copyOverlaysTo(result);
        return result;
    }

    private void copyOverlaysTo(RunVariableContext target) {
        synchronized (overlaysLock) {
            synchronized (target.overlaysLock) {
                target.overlays.addAll(overlays);
            }
        }
    }

    /** 将临时 loop 值压入最高优先级，关闭句柄后恢复原值。 */
    public Overlay pushOverlay(Map<String, JsonNode> values) {
        Map<String, JsonNode> copy = immutableScope(values, "loop");
        synchronized (overlaysLock) {
            overlays.push(copy);
        }
        return new Overlay(copy);
    }

    public JsonNode lookup(String name) {
        if (name == null || !NAME.matcher(name).matches() || name.startsWith("secret:")) return null;
        synchronized (overlaysLock) {
            for (Map<String, JsonNode> overlay : overlays) {
                JsonNode value = overlay.get(name);
                if (value != null) return value.deepCopy();
            }
        }
        JsonNode value = extracted.get(name);
        if (value != null) return value.deepCopy();
        value = dataRow.get(name);
        if (value != null) return value.deepCopy();
        value = caseVariables.get(name);
        if (value != null) return value.deepCopy();
        value = scenario.get(name);
        if (value != null) return value.deepCopy();
        value = environment.get(name);
        return value == null ? null : value.deepCopy();
    }

    public String resolveText(String input) {
        if (input == null) throw new IllegalArgumentException("待解析文本不能为空");
        Matcher matcher = TOKEN.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(2) != null
                    ? variableText(matcher.group(2)) : builtin(matcher.group(3));
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    public JsonNode resolveNode(JsonNode input) {
        if (input == null || input.isNull()) return input;
        if (input.isTextual()) {
            String text = input.textValue();
            Matcher whole = WHOLE_VARIABLE.matcher(text);
            if (whole.matches()) {
                JsonNode value = lookup(whole.group(1));
                if (value != null) return value;
                if (isSecretReference(whole.group(1))) return TextNode.valueOf(text);
                throw undefined(whole.group(1), "");
            }
            return TextNode.valueOf(resolveText(text));
        }
        if (input.isObject()) {
            var output = JsonNodeFactory.instance.objectNode();
            input.fields().forEachRemaining(entry -> output.set(entry.getKey(), resolveNode(entry.getValue())));
            return output;
        }
        if (input.isArray()) {
            var output = JsonNodeFactory.instance.arrayNode();
            input.forEach(item -> output.add(resolveNode(item)));
            return output;
        }
        return input.deepCopy();
    }

    /** 对任意动态字段做无外部副作用预检。路径只包含字段名/数组索引，不包含值。 */
    public PreflightResult preflight(JsonNode input, String path) {
        return preflight(input, path, Set.of());
    }

    /**
     * 场景启动前预检时允许调用方声明已由前序步骤生产、但尚未实际提取的变量。
     * 这些变量仍会在对应步骤执行时再次按当前上下文预检；这里只解决 producer/consumer
     * 的静态可达性，不能把任意未声明变量变成可用变量。
     */
    public PreflightResult preflight(JsonNode input, String path, Set<String> availableVariables) {
        String safePath = path == null || path.isBlank() ? "$" : path;
        Set<String> available = availableVariables == null ? Set.of() : Set.copyOf(availableVariables);
        try {
            preflightNode(input, safePath, available);
            return PreflightResult.ok();
        } catch (VariableUndefinedException exception) {
            return PreflightResult.failure(exception.code(), exception.path().isBlank() ? safePath : exception.path(),
                    "动态字段未定义");
        } catch (IllegalArgumentException exception) {
            return PreflightResult.failure("VARIABLE_INVALID", safePath, "动态字段格式无效");
        }
    }

    public JsonNode serializeScopes(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "ObjectMapper 不能为空");
        var output = mapper.createObjectNode();
        output.set("environment", objectScope(mapper, environment));
        output.set("scenario", objectScope(mapper, scenario));
        output.set("caseVariables", objectScope(mapper, caseVariables));
        output.set("dataRow", objectScope(mapper, dataRow));
        Map<String, JsonNode> extractedCopy = new LinkedHashMap<>();
        extracted.forEach((key, value) -> extractedCopy.put(key, value.deepCopy()));
        output.set("extracted", objectScope(mapper, extractedCopy));
        return output;
    }

    public static String validateWritableName(String name) {
        if (name == null || name.startsWith("secret:") || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("变量名不合法或不能写入密钥命名空间");
        }
        return name;
    }

    private void preflightNode(JsonNode node, String path, Set<String> availableVariables) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            Matcher matcher = TOKEN.matcher(node.textValue());
            while (matcher.find()) {
                if (matcher.group(2) != null) {
                    String name = matcher.group(2);
                    if (isSecretReference(name)) continue;
                    if (lookup(name) == null && !availableVariables.contains(name)) throw undefined(name, path);
                } else {
                    validateBuiltin(matcher.group(3));
                }
            }
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                preflightNode(field.getValue(), path + "." + field.getKey(), availableVariables);
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                preflightNode(node.get(index), path + "[" + index + "]", availableVariables);
            }
        }
    }

    private String variableText(String name) {
        if (isSecretReference(name)) return "${" + name + "}";
        if (name.startsWith("secret:")) throw new IllegalArgumentException("密钥引用格式不合法");
        validateName(name);
        JsonNode value = lookup(name);
        if (value == null) throw undefined(name, "");
        return scalar(value);
    }

    private static String scalar(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        return value.isTextual() ? value.textValue() : value.toString();
    }

    private static String builtin(String expression) {
        validateBuiltin(expression);
        String[] parts = expression.split(":", 2);
        return switch (parts[0]) {
            case "uuid" -> UUID.randomUUID().toString();
            case "timestamp" -> Instant.now().toString();
            case "date" -> Instant.now().atOffset(ZoneOffset.UTC).toLocalDate().toString();
            case "formatdate" -> formatDate(parts.length == 1 ? "yyyy-MM-dd" : parts[1]);
            case "randomint" -> randomInt(parts.length == 1 ? "0,999999" : parts[1]);
            case "randomstring" -> randomString(parts.length == 1 ? "8" : parts[1]);
            default -> throw new IllegalArgumentException("不支持的内置函数");
        };
    }

    private static void validateBuiltin(String expression) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("内置函数不能为空");
        String[] parts = expression.split(":", 2);
        String name = parts[0];
        switch (name) {
            case "uuid", "timestamp", "date" -> {
                if (parts.length > 1) throw new IllegalArgumentException("内置函数参数无效");
            }
            case "formatdate" -> {
                BuiltinFunctionContract.validateFormatDatePattern(
                        parts.length == 1 ? BuiltinFunctionContract.DEFAULT_FORMAT_DATE_PATTERN : parts[1]);
            }
            case "randomint" -> parseRandomInt(parts.length == 1 ? "0,999999" : parts[1]);
            case "randomstring" -> {
                int length = parseInt(parts.length == 1 ? "8" : parts[1], "随机字符串长度");
                if (length < 1 || length > 256) throw new IllegalArgumentException("随机字符串长度必须为 1 到 256");
            }
            default -> throw new IllegalArgumentException("不支持的内置函数");
        }
    }

    private static String randomInt(String range) {
        long[] bounds = parseRandomInt(range);
        BigInteger min = BigInteger.valueOf(bounds[0]);
        BigInteger span = BigInteger.valueOf(bounds[1]).subtract(min).add(BigInteger.ONE);
        if (span.equals(BigInteger.ONE)) return min.toString();
        BigInteger candidate;
        int bytes = (span.bitLength() + 7) / 8;
        do {
            byte[] random = new byte[bytes];
            RANDOM.nextBytes(random);
            candidate = new BigInteger(1, random);
        } while (candidate.compareTo(span) >= 0);
        return min.add(candidate).toString();
    }

    private static long[] parseRandomInt(String range) {
        String[] bounds = range.split(",", -1);
        if (bounds.length != 2) throw new IllegalArgumentException("随机整数必须使用 min,max");
        try {
            long min = Long.parseLong(bounds[0].strip());
            long max = Long.parseLong(bounds[1].strip());
            if (min < 0 || max > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("随机整数范围必须在 0 到 2147483647 之间");
            }
            if (min > max) throw new IllegalArgumentException("随机整数范围无效");
            return new long[]{min, max};
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("随机整数参数无效", exception);
        }
    }

    private static String randomString(String lengthText) {
        int length = parseInt(lengthText, "随机字符串长度");
        if (length < 1 || length > 256) throw new IllegalArgumentException("随机字符串长度必须为 1 到 256");
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) value.append(ALPHANUMERIC[RANDOM.nextInt(ALPHANUMERIC.length)]);
        return value.toString();
    }

    private static int parseInt(String value, String label) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + "参数无效", exception);
        }
    }

    private static String formatDate(String pattern) {
        return BuiltinFunctionContract.formatDateFormatter(pattern)
                .withZone(ZoneOffset.UTC).format(Instant.now());
    }

    private static boolean isSecretReference(String value) {
        return value != null && SECRET.matcher(value).matches();
    }

    private static void validateName(String name) {
        if (name == null || name.startsWith("secret:") || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("变量名不合法");
        }
    }

    private static VariableUndefinedException undefined(String name, String path) {
        return new VariableUndefinedException(name, path);
    }

    private static Map<String, JsonNode> scope(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) return Map.of();
        if (!value.isObject()) throw new IllegalArgumentException(field + " 必须是对象");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        value.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private static Map<String, JsonNode> immutableScope(Map<String, JsonNode> values, String field) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        values.forEach((name, value) -> copy.put(validateWritableName(name), copy(value, field + "." + name)));
        return Map.copyOf(copy);
    }

    private static JsonNode copy(JsonNode value, String path) {
        return value == null ? JsonNodeFactory.instance.nullNode() : value.deepCopy();
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode objectScope(ObjectMapper mapper,
                                                                                Map<String, JsonNode> values) {
        var output = mapper.createObjectNode();
        values.forEach((name, value) -> output.set(name, value.deepCopy()));
        return output;
    }

    public final class Overlay implements AutoCloseable {
        private final Map<String, JsonNode> values;
        private boolean closed;

        private Overlay(Map<String, JsonNode> values) {
            this.values = values;
        }

        @Override
        public void close() {
            synchronized (overlaysLock) {
                if (!closed) {
                    overlays.removeFirstOccurrence(values);
                    closed = true;
                }
            }
        }
    }

    public record PreflightResult(boolean valid, String code, String path, String message) {
        static PreflightResult ok() {
            return new PreflightResult(true, "", "", "");
        }

        static PreflightResult failure(String code, String path, String message) {
            return new PreflightResult(false, code, path, message);
        }
    }

    public static final class VariableUndefinedException extends IllegalArgumentException {
        private final String variable;
        private final String path;

        VariableUndefinedException(String variable, String path) {
            super("VARIABLE_UNDEFINED: " + variable);
            this.variable = variable;
            this.path = path == null ? "" : path;
        }

        public String code() {
            return "VARIABLE_UNDEFINED";
        }

        public String variable() {
            return variable;
        }

        public String path() {
            return path;
        }
    }

    public static final class Builder {
        private final String runId;
        private final String rowId;
        private Map<String, JsonNode> environment = Map.of();
        private Map<String, JsonNode> scenario = Map.of();
        private Map<String, JsonNode> caseVariables = Map.of();
        private Map<String, JsonNode> dataRow = Map.of();
        private Map<String, JsonNode> extracted = Map.of();

        private Builder(String runId, String rowId) {
            this.runId = runId;
            this.rowId = rowId;
        }

        public Builder environment(Map<String, JsonNode> values) { environment = values; return this; }
        public Builder scenario(Map<String, JsonNode> values) { scenario = values; return this; }
        public Builder caseVariables(Map<String, JsonNode> values) { caseVariables = values; return this; }
        public Builder dataRow(Map<String, JsonNode> values) { dataRow = values; return this; }
        public Builder extracted(Map<String, JsonNode> values) { extracted = values; return this; }

        public RunVariableContext build() {
            return new RunVariableContext(runId, rowId, environment, scenario, caseVariables, dataRow, extracted);
        }
    }
}
