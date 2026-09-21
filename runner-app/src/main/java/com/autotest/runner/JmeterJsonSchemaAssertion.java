package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 受控 JSON Schema 子集断言。只读取响应正文和声明式 schema，不执行脚本或表达式。
 * 支持 type、required、properties、items、additionalProperties、enum、minItems/maxItems。
 */
public final class JmeterJsonSchemaAssertion extends AbstractTestElement implements Assertion {

    public static final String SCHEMA_PROPERTY = "autotest.schema";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SCHEMA_LENGTH = 256 * 1024;

    public void setSchema(String schema) {
        if (schema == null || schema.isBlank() || schema.length() > MAX_SCHEMA_LENGTH) {
            throw new IllegalArgumentException("JSON Schema 不能为空且不能超过 256KB");
        }
        setProperty(SCHEMA_PROPERTY, schema);
    }

    private String schemaValue() {
        return getPropertyAsString(SCHEMA_PROPERTY, "");
    }

    @Override
    public AssertionResult getResult(SampleResult sampleResult) {
        AssertionResult result = new AssertionResult(getName());
        try {
            JsonNode schema = JSON.readTree(schemaValue());
            JsonNode actual = JSON.readTree(sampleResult.getResponseDataAsString());
            List<String> failures = new ArrayList<>();
            validate(schema, actual, "$", failures);
            if (!failures.isEmpty()) {
                result.setFailure(true);
                result.setFailureMessage(String.join("; ", failures));
            }
        } catch (Exception exception) {
            result.setError(true);
            result.setFailureMessage("JSON Schema 断言无法解析响应或 schema");
        }
        return result;
    }

    private static void validate(JsonNode schema, JsonNode actual, String path, List<String> failures) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            failures.add(path + " schema 为空");
            return;
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray() && !contains(enumValues, actual)) {
            failures.add(path + " 不在 enum 中");
        }
        JsonNode type = schema.get("type");
        if (type != null && type.isTextual() && !matchesType(type.textValue(), actual)) {
            failures.add(path + " 类型不匹配，期望 " + type.textValue());
            return;
        }
        if (actual == null || actual.isNull()) return;
        if (actual.isObject()) validateObject(schema, actual, path, failures);
        if (actual.isArray()) validateArray(schema, actual, path, failures);
    }

    private static void validateObject(JsonNode schema, JsonNode actual, String path, List<String> failures) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode name : required) {
                if (name.isTextual() && !actual.has(name.textValue())) {
                    failures.add(path + " 缺少字段 " + name.textValue());
                }
            }
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (actual.has(field.getKey())) {
                    validate(field.getValue(), actual.get(field.getKey()), path + "." + field.getKey(), failures);
                }
            }
            if (schema.path("additionalProperties").isBoolean()
                    && !schema.path("additionalProperties").asBoolean()) {
                Iterator<String> names = actual.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    if (!properties.has(name)) failures.add(path + " 不允许额外字段 " + name);
                }
            }
        }
    }

    private static void validateArray(JsonNode schema, JsonNode actual, String path, List<String> failures) {
        if (schema.has("minItems") && actual.size() < schema.path("minItems").asInt()) {
            failures.add(path + " 数组元素过少");
        }
        if (schema.has("maxItems") && actual.size() > schema.path("maxItems").asInt()) {
            failures.add(path + " 数组元素过多");
        }
        JsonNode items = schema.get("items");
        if (items != null && items.isObject()) {
            for (int index = 0; index < actual.size(); index++) {
                validate(items, actual.get(index), path + "[" + index + "]", failures);
            }
        }
    }

    private static boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private static boolean contains(JsonNode values, JsonNode actual) {
        for (JsonNode value : values) {
            if (value.equals(actual)) return true;
        }
        return false;
    }
}
