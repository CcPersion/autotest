package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行前的统一变量解析器。变量只在本次运行上下文内解析，不修改输入 JSON。
 * 优先级固定为：提取值 > 数据行 > 接口用例 > 场景 > 环境。
 */
public final class VariableResolver {

    private static final Pattern SECRET = Pattern.compile("secret:([A-Za-z0-9][A-Za-z0-9._-]*)");

    private final RunVariableContext context;
    private final Map<String, String> secrets;

    public VariableResolver(Scopes scopes) {
        this(scopes, Map.of());
    }

    public VariableResolver(Scopes scopes, Map<String, String> secrets) {
        Objects.requireNonNull(scopes, "变量作用域不能为空");
        this.context = RunVariableContext.builder("legacy-variable-resolver")
                .environment(scopes.environment())
                .scenario(scopes.scenario())
                .caseVariables(scopes.caseVariables())
                .dataRow(scopes.dataRow())
                .extracted(scopes.extracted())
                .build();
        this.secrets = immutable(secrets, "密钥上下文");
    }

    /** 解析字符串；混合文本中的变量会转为字符串，完整变量保留 JSON 类型由 resolveNode 处理。 */
    public String resolveText(String input) {
        return resolveSecrets(context.resolveText(input));
    }

    /** 递归解析 JSON；完整的普通变量会保留数字、布尔、对象和数组类型。 */
    public JsonNode resolveNode(JsonNode input) {
        return resolveSecrets(context.resolveNode(input));
    }

    /** 提取器只能写普通变量，不能覆盖或伪造密钥引用。 */
    public static void validateWritableName(String name) {
        RunVariableContext.validateWritableName(name);
    }

    public RunVariableContext context() {
        return context;
    }

    private String resolveSecrets(String value) {
        if (secrets.isEmpty()) return value;
        Matcher matcher = Pattern.compile("\\$\\{(secret:[A-Za-z0-9][A-Za-z0-9._-]*)}").matcher(value);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String name = secretName(matcher.group(1));
            String replacement = name == null ? matcher.group() : secrets.getOrDefault(name, matcher.group());
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private JsonNode resolveSecrets(JsonNode value) {
        if (value == null || value.isNull()) return value;
        if (value.isTextual()) return TextNode.valueOf(resolveSecrets(value.textValue()));
        if (value.isObject()) {
            var output = JsonNodeFactory.instance.objectNode();
            value.fields().forEachRemaining(entry -> output.set(entry.getKey(), resolveSecrets(entry.getValue())));
            return output;
        }
        if (value.isArray()) {
            var output = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> output.add(resolveSecrets(item)));
            return output;
        }
        return value.deepCopy();
    }

    private static String secretName(String reference) {
        Matcher matcher = SECRET.matcher(reference);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static Map<String, String> immutable(Map<String, String> values, String field) {
        if (values == null) return Map.of();
        Map<String, String> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(Objects.requireNonNull(key, field + " key 不能为空"),
                Objects.requireNonNull(value, field + " value 不能为空")));
        return Map.copyOf(copy);
    }

    public record Scopes(Map<String, JsonNode> environment,
                         Map<String, JsonNode> scenario,
                         Map<String, JsonNode> caseVariables,
                         Map<String, JsonNode> dataRow,
                         Map<String, JsonNode> extracted) {
        public Scopes {
            environment = copy(environment);
            scenario = copy(scenario);
            caseVariables = copy(caseVariables);
            dataRow = copy(dataRow);
            extracted = copy(extracted);
        }

        private static Map<String, JsonNode> copy(Map<String, JsonNode> values) {
            if (values == null) return Map.of();
            Map<String, JsonNode> copy = new LinkedHashMap<>();
            values.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "变量名不能为空"),
                    Objects.requireNonNull(value, "变量值不能为空").deepCopy()));
            return Map.copyOf(copy);
        }
    }
}
