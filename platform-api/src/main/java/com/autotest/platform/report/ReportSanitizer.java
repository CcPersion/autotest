package com.autotest.platform.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** 报告入库前的统一脱敏和正文截断。 */
final class ReportSanitizer {

    private static final int MAX_TEXT_LENGTH = 1_048_576;
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "secret", "secretkey", "token", "accesstoken", "refreshtoken",
            "apikey", "authorization", "cookie", "cookies", "setcookie", "xapikey");
    private static final Pattern QUERY_PARAMETER = Pattern.compile("([?&])([^&#=]*)(=)([^&#]*)");
    private static final Pattern SECRET_FUNCTION = Pattern.compile("\\$\\{__autotestSecret\\([^{}]*\\)\\}");
    private static final Pattern SENSITIVE_TEXT = Pattern.compile(
            "(?i)([\\\"']?(?:access[_-]?token|refresh[_-]?token|x[-_]?api[-_]?key|api[_-]?key|token|password|secret|authorization|cookie|set[-_]?cookie)[\\\"']?\\s*[:=]\\s*[\\\"'])([^\\\"']*)([\\\"'])");
    private static final String TRUNCATION_MARKER = "…[已截断]";
    private static final Pattern AUTH_VALUE = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[^\\s,;]+");
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReportSanitizer() {
    }

    static StepResultWrite sanitize(StepResultWrite write) {
        Set<String> knownSensitiveValues = knownSensitiveValues(write);
        return new StepResultWrite(write.stepId(), write.resultKey(), write.sequenceNo(), write.status(),
                write.durationMs(), sanitize(write.requestSummary()), sanitize(write.responseSummary()),
                sanitizeAssertions(write.assertions(), knownSensitiveValues), sanitizeExtractions(write.extractions()),
                sanitizeMessages(write.errorSummary(), knownSensitiveValues),
                write.startedAt(), write.finishedAt());
    }

    private static JsonNode sanitizeAssertions(JsonNode node, Set<String> knownSensitiveValues) {
        if (node == null || !node.isArray()) return sanitize(node);
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        node.forEach(value -> {
            JsonNode sanitized = sanitize(value);
            if (sanitized != null && sanitized.isObject()) {
                ObjectNode assertion = (ObjectNode) sanitized;
                String expression = assertion.path("expression").asText("");
                if (assertion.has("actual") && sensitiveExpression(expression)) {
                    assertion.put("actual", "***");
                }
                sanitizeMessageField(assertion, "message", knownSensitiveValues);
                sanitizeMessageField(assertion, "diagnostic", knownSensitiveValues);
            }
            result.add(sanitized);
        });
        return result;
    }

    private static JsonNode sanitizeMessages(JsonNode node, Set<String> knownSensitiveValues) {
        JsonNode sanitized = sanitize(node);
        if (sanitized != null && sanitized.isObject()) {
            ObjectNode object = (ObjectNode) sanitized;
            sanitizeMessageField(object, "message", knownSensitiveValues);
            sanitizeMessageField(object, "detail", knownSensitiveValues);
            sanitizeMessageField(object, "diagnostic", knownSensitiveValues);
        }
        return sanitized;
    }

    private static void sanitizeMessageField(ObjectNode object, String field,
                                             Set<String> knownSensitiveValues) {
        if (object.has(field) && object.get(field).isTextual()) {
            object.put(field, sanitizeText(object.get(field).textValue(), knownSensitiveValues));
        }
    }

    private static Set<String> knownSensitiveValues(StepResultWrite write) {
        Set<String> values = new LinkedHashSet<>();
        collectSensitiveValues(write.requestSummary(), "", values);
        collectSensitiveValues(write.responseSummary(), "", values);
        collectSensitiveValues(write.assertions(), "", values);
        if (write.assertions() != null && write.assertions().isArray()) {
            write.assertions().forEach(assertion -> {
                if (assertion != null && assertion.isObject()
                        && sensitiveExpression(assertion.path("expression").asText(""))) {
                    collectSensitiveValues(assertion.get("actual"), "token", values);
                    collectSensitiveValues(assertion.get("expected"), "token", values);
                }
            });
        }
        return values;
    }

    private static void collectSensitiveValues(JsonNode node, String fieldName, Set<String> values) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectSensitiveValues(entry.getValue(), entry.getKey(), values));
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectSensitiveValues(item, fieldName, values));
            return;
        }
        if (!node.isTextual()) return;
        String text = node.textValue();
        Matcher matcher = SENSITIVE_TEXT.matcher(text);
        while (matcher.find()) addKnownValue(values, matcher.group(2));
        if (sensitiveKey(fieldName)) {
            addKnownValue(values, text);
        } else if ("body".equalsIgnoreCase(fieldName)) {
            try {
                JsonNode parsed = JSON.readTree(text);
                if (parsed != null) collectSensitiveValues(parsed, "", values);
            } catch (Exception ignored) {
                // 非 JSON 正文仍由 key=value 规则覆盖。
            }
        }
    }

    private static void addKnownValue(Set<String> values, String value) {
        if (value == null || value.isBlank() || "***".equals(value)) return;
        values.add(value);
    }

    private static String sanitizeText(String value, Set<String> knownSensitiveValues) {
        String sanitized = value == null ? "" : value;
        for (String item : knownSensitiveValues.stream()
                .filter(valueItem -> valueItem != null && !valueItem.isBlank() && !"***".equals(valueItem))
                .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            sanitized = sanitized.replace(item, "***");
        }
        sanitized = SECRET_FUNCTION.matcher(sanitized).replaceAll("***");
        sanitized = SENSITIVE_TEXT.matcher(sanitized).replaceAll("$1***$3");
        sanitized = AUTH_VALUE.matcher(sanitized).replaceAll("$1 ***");
        if (sanitized.length() > MAX_TEXT_LENGTH) {
            sanitized = sanitized.substring(0, MAX_TEXT_LENGTH - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
        }
        return sanitized;
    }

    private static JsonNode sanitizeExtractions(JsonNode node) {
        if (node == null || !node.isArray()) {
            return sanitize(node);
        }
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        node.forEach(value -> {
            JsonNode sanitized = sanitize(value);
            if (sanitized != null && sanitized.isObject()) {
                String variable = sanitized.path("variable").asText("").toLowerCase(Locale.ROOT)
                        .replace("_", "").replace("-", "");
                if (SENSITIVE_KEYS.stream().anyMatch(variable::contains)) {
                    ((ObjectNode) sanitized).put("value", "***");
                }
            }
            result.add(sanitized);
        });
        return result;
    }

    private static JsonNode sanitize(JsonNode node) {
        if (node == null) {
            return JsonNodeFactory.instance.objectNode();
        }
        if (node.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalized = field.getKey().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                if (SENSITIVE_KEYS.contains(normalized)) {
                    result.put(field.getKey(), "***");
                } else {
                    result.set(field.getKey(), sanitize(field.getValue(), field.getKey()));
                }
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> result.add(sanitize(value, null)));
            return result;
        }
        return node.deepCopy();
    }

    private static JsonNode sanitize(JsonNode node, String fieldName) {
        if (node != null && node.isTextual()) {
            String value = node.textValue();
            if ("url".equalsIgnoreCase(fieldName)) {
                value = sanitizeUrl(value);
            }
            value = SECRET_FUNCTION.matcher(value).replaceAll("***");
            value = SENSITIVE_TEXT.matcher(value).replaceAll("$1***$3");
            value = AUTH_VALUE.matcher(value).replaceAll("$1 ***");
            if (value.length() > MAX_TEXT_LENGTH) {
                value = value.substring(0, MAX_TEXT_LENGTH - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
            }
            return JsonNodeFactory.instance.textNode(value);
        }
        return sanitize(node);
    }

    private static String sanitizeUrl(String value) {
        Matcher matcher = QUERY_PARAMETER.matcher(value == null ? "" : value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(2);
            String decoded;
            try {
                decoded = URLDecoder.decode(key, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                decoded = key;
            }
            String normalized = normalizeKey(decoded);
            if (SENSITIVE_KEYS.contains(normalized)) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(
                        matcher.group(1) + key + matcher.group(3) + "***"));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean sensitiveKey(String key) {
        String normalized = normalizeKey(key);
        return SENSITIVE_KEYS.stream().anyMatch(item -> normalized.equals(item) || normalized.startsWith(item));
    }

    private static boolean sensitiveExpression(String expression) {
        if (expression == null || expression.isBlank()) return false;
        for (String candidate : expression.split("[^A-Za-z0-9_-]+")) {
            String normalized = normalizeKey(candidate);
            if (SENSITIVE_KEYS.stream().anyMatch(item -> normalized.equals(item) || normalized.startsWith(item))) {
                return true;
            }
        }
        return false;
    }
}
