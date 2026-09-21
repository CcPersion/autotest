package com.autotest.contracts.execution;

import com.autotest.contracts.util.ContractChecks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 一次运行解析后的不可变执行计划。 */
public record ExecutionPlan(
        String planId,
        Map<String, JsonNode> assetContent,
        Map<String, JsonNode> variables,
        List<String> secretRefs,
        Map<String, String> fileChecksums,
        String jmeterVersion,
        Instant createdAt
) {

    private static final Pattern SECRET_REFERENCE =
            Pattern.compile("\\$\\{secret:[A-Za-z0-9][A-Za-z0-9._-]*}");
    private static final Pattern AUTHORIZATION_TEMPLATE =
            Pattern.compile("(?:Bearer|Basic) \\$\\{secret:[A-Za-z0-9][A-Za-z0-9._-]*}");
    private static final Set<String> SENSITIVE_KEY_NAMES = Set.of(
            "password", "secret", "secretkey", "token", "accesstoken", "refreshtoken",
            "apikey", "authorization", "proxyauthorization", "cookie", "cookies", "setcookie",
            "xapikey");
    private static final Set<String> SENSITIVE_HEADER_NAMES = Set.of(
            "proxyauthorization", "cookie", "setcookie", "xapikey", "apikey");

    public ExecutionPlan {
        planId = ContractChecks.requiredText(planId, "planId");
        assetContent = immutableJsonMap(assetContent, "assetContent");
        variables = immutableJsonMap(variables, "variables");
        secretRefs = immutableSecretRefs(secretRefs);
        fileChecksums = fileChecksums == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(fileChecksums));
        jmeterVersion = ContractChecks.requiredText(jmeterVersion, "jmeterVersion");
        if (createdAt == null) {
            throw new NullPointerException("createdAt 不能为空");
        }
    }

    /** 返回与内部状态脱离的深拷贝，调用方不能通过 JsonNode 修改计划。 */
    @Override
    public Map<String, JsonNode> assetContent() {
        return copyJsonMap(assetContent);
    }

    /** 返回与内部状态脱离的深拷贝，调用方不能通过 JsonNode 修改计划。 */
    @Override
    public Map<String, JsonNode> variables() {
        return copyJsonMap(variables);
    }

    private static Map<String, JsonNode> immutableJsonMap(Map<String, JsonNode> values, String fieldName) {
        if (values == null) {
            return Map.of();
        }
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : values.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), fieldName + " key 不能为空");
            JsonNode value = normalizeJson(entry.getValue());
            if (isHeaderContainerKey(key) && isHeaderLineArray(value)) {
                validateHeaderContainer(value, fieldName + "." + key);
            } else if (isCookieKey(key)) {
                validateCookieContainer(value, fieldName + "." + key);
            } else if (isSensitiveKey(key) && !isAllowedSensitiveValue(key, value)) {
                throw new IllegalArgumentException(fieldName + "." + key
                        + " 只能使用 ${secret:name} 占位符");
            }
            validateSensitiveKeys(value, fieldName + "." + key);
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<String> immutableSecretRefs(List<String> refs) {
        if (refs == null) {
            return List.of();
        }
        List<String> copy = new ArrayList<>(refs.size());
        for (String ref : refs) {
            if (!isSecretReference(ref)) {
                throw new IllegalArgumentException("secretRefs 只能使用 ${secret:name} 占位符");
            }
            copy.add(ref);
        }
        return List.copyOf(copy);
    }

    private static Map<String, JsonNode> copyJsonMap(Map<String, JsonNode> values) {
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : values.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().deepCopy());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static JsonNode normalizeJson(JsonNode value) {
        if (value == null || value.isNull()) {
            return NullNode.getInstance();
        }
        if (value.isNumber()) {
            BigDecimal decimal = value.decimalValue().stripTrailingZeros();
            if (decimal.scale() < 0) {
                decimal = decimal.setScale(0);
            }
            return DecimalNode.valueOf(decimal);
        }
        if (value.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                copy.set(field.getKey(), normalizeJson(field.getValue()));
            }
            return copy;
        }
        if (value.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : value) {
                copy.add(normalizeJson(item));
            }
            return copy;
        }
        if (value.isTextual() || value.isBoolean()) {
            return value.deepCopy();
        }
        throw new IllegalArgumentException("只允许 JSON 标量、对象、数组和 null");
    }

    private static void validateSensitiveKeys(JsonNode value, String path) {
        if (value.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String childPath = path + "." + field.getKey();
                if (isHeaderContainerKey(field.getKey()) && isHeaderLineArray(field.getValue())) {
                    validateHeaderContainer(field.getValue(), childPath);
                } else if (isCookieKey(field.getKey())) {
                    validateCookieContainer(field.getValue(), childPath);
                } else if (isSensitiveKey(field.getKey())
                        && !isAllowedSensitiveValue(field.getKey(), field.getValue())) {
                    throw new IllegalArgumentException(childPath + " 只能使用 ${secret:name} 占位符");
                }
                validateSensitiveKeys(field.getValue(), childPath);
            }
        } else if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                validateSensitiveKeys(value.get(index), path + "[" + index + "]");
            }
        }
    }

    private static void validateHeaderContainer(JsonNode headers, String path) {
        for (int index = 0; index < headers.size(); index++) {
            JsonNode header = headers.get(index);
            JsonNode name = header.get("name");
            JsonNode value = header.get("value");
            if (name != null && name.isTextual() && value != null) {
                String normalizedName = normalizeKey(name.textValue());
                if (normalizedName.equals("authorization")
                        && !isSafeAuthorizationValue(value)) {
                    throw new IllegalArgumentException(path + "[" + index
                            + "].value 只能使用安全 Authorization 模板");
                }
                if (SENSITIVE_HEADER_NAMES.contains(normalizedName)
                        && !isSecretReference(value)) {
                    throw new IllegalArgumentException(path + "[" + index
                            + "].value 只能使用 ${secret:name} 占位符");
                }
            }
        }
    }

    private static boolean isHeaderLineArray(JsonNode value) {
        if (!value.isArray()) {
            return false;
        }
        for (JsonNode item : value) {
            if (!item.isObject() || !item.has("name") || !item.has("value")) {
                return false;
            }
        }
        return true;
    }

    private static void validateCookieContainer(JsonNode cookies, String path) {
        if (isSecretReference(cookies)) {
            return;
        }
        if (cookies.isArray()) {
            for (int index = 0; index < cookies.size(); index++) {
                JsonNode cookie = cookies.get(index);
                if (!cookie.isObject() || !isSecretReference(cookie.get("value"))) {
                    throw new IllegalArgumentException(path + "[" + index
                            + "].value 只能使用 ${secret:name} 占位符");
                }
            }
            return;
        }
        if (cookies.isObject()) {
            if (cookies.has("value")) {
                if (!isSecretReference(cookies.get("value"))) {
                    throw new IllegalArgumentException(path
                            + ".value 只能使用 ${secret:name} 占位符");
                }
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = cookies.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!isSecretReference(field.getValue())) {
                    throw new IllegalArgumentException(path + "." + field.getKey()
                            + " 只能使用 ${secret:name} 占位符");
                }
            }
            return;
        }
        throw new IllegalArgumentException(path + " 只能使用 ${secret:name} 占位符或结构化 Cookie");
    }

    private static boolean isAllowedSensitiveValue(String key, JsonNode value) {
        if (isAuthorizationKey(key)) {
            return isSafeAuthorizationValue(value);
        }
        return isSecretReference(value);
    }

    private static boolean isSafeAuthorizationValue(JsonNode value) {
        return isSecretReference(value)
                || (value != null && value.isTextual()
                && AUTHORIZATION_TEMPLATE.matcher(value.textValue()).matches());
    }

    private static boolean isHeaderContainerKey(String key) {
        return normalizeKey(key).equals("headers");
    }

    private static boolean isCookieKey(String key) {
        String normalized = normalizeKey(key);
        return normalized.equals("cookie") || normalized.equals("cookies");
    }

    private static boolean isAuthorizationKey(String key) {
        return normalizeKey(key).equals("authorization");
    }

    private static boolean isSensitiveKey(String key) {
        return SENSITIVE_KEY_NAMES.contains(normalizeKey(key));
    }

    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static boolean isSecretReference(String value) {
        return value != null && SECRET_REFERENCE.matcher(value).matches();
    }

    private static boolean isSecretReference(JsonNode value) {
        return value != null && value.isTextual() && isSecretReference(value.textValue());
    }
}
