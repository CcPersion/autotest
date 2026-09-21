package com.autotest.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将已脱敏的 JTL 摘要按步骤幂等回传 Platform API。 */
public final class JtlResultUploader {

    private static final int MAX_EVIDENCE_TEXT_LENGTH = 4096;
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "secret", "secretkey", "token", "accesstoken", "refreshtoken",
            "apikey", "authorization", "cookie", "cookies", "setcookie", "xapikey");
    private static final Pattern SENSITIVE_TEXT = Pattern.compile(
            "(?i)([\\\"']?(?:access[_-]?token|refresh[_-]?token|x[-_]?api[-_]?key|api[_-]?key|token|password|secret|authorization|cookie|set[-_]?cookie)[\\\"']?\\s*[:=]\\s*[\\\"'])([^\\\"']*)([\\\"'])");
    private static final Pattern AUTH_VALUE = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[^\\s,;]+");
    private static final Pattern SECRET_FUNCTION = Pattern.compile("\\$\\{__autotestSecret\\([^{}]*\\)\\}");
    private static final Pattern RULE_INDEX = Pattern.compile("(?i)\\bruleIndex\\s*[=:]\\s*(\\d+)\\b");

    private final URI endpoint;
    private final String token;
    private final ObjectMapper json;
    private final HttpClient http;

    public JtlResultUploader(String platformBaseUrl, String token) {
        if (platformBaseUrl == null || platformBaseUrl.isBlank() || token == null || token.isBlank()) {
            throw new IllegalArgumentException("结果回传地址和 Runner Token 不能为空");
        }
        this.endpoint = URI.create(stripTrailingSlash(platformBaseUrl));
        this.token = token;
        this.json = new ObjectMapper();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public void upload(UUID runId, List<JtlSample> samples) throws IOException, InterruptedException {
        upload(runId, samples, null);
    }

    /** 上传 HTTP 计划上下文，补齐受控 method/body 证据；非 HTTP 步骤继续使用旧入口。 */
    public void upload(UUID runId, List<JtlSample> samples, JmeterPlan plan) throws IOException, InterruptedException {
        if (runId == null || samples == null) {
            throw new IllegalArgumentException("运行 ID 和 JTL 样本不能为空");
        }
        for (int index = 0; index < samples.size(); index++) {
            JtlSample sample = samples.get(index);
            ObjectNode body = payload(runId, sample, index, plan);
            URI target = endpoint.resolve("/api/v1/internal/runs/" + runId + "/step-results");
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("X-Runner-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("结果回传失败，HTTP 状态码: " + response.statusCode());
            }
        }
    }

    private ObjectNode payload(UUID runId, JtlSample sample, int index, JmeterPlan plan) {
        UUID stepId;
        try {
            stepId = UUID.fromString(sample.stepId());
        } catch (IllegalArgumentException ignored) {
            stepId = UUID.nameUUIDFromBytes((runId + ":" + sample.stepId()).getBytes(StandardCharsets.UTF_8));
        }
        Set<String> knownSensitiveValues = knownSensitiveValues(plan, sample);
        ObjectNode body = json.createObjectNode();
        body.put("stepId", stepId.toString());
        body.put("resultKey", sample.stepId() + "#" + index);
        body.put("sequenceNo", index);
        body.put("status", sample.success() ? "PASSED" : "FAILED");
        body.put("durationMs", sample.elapsedMs());
        ObjectNode requestSummary = body.putObject("requestSummary");
        String method = plan == null ? sample.method() : plan.method();
        requestSummary.put("method", method == null || method.isBlank() ? "UNKNOWN" : method);
        requestSummary.put("url", sanitizeMessage(sample.url(), knownSensitiveValues));
        requestSummary.set("headers", requestHeaders(plan, knownSensitiveValues));
        if (plan != null && plan.body() != null && !"NONE".equals(plan.body().type())) {
            requestSummary.set("body", bounded(plan.body().value(), knownSensitiveValues));
        } else if (plan == null && sample.requestBody() != null && !sample.requestBody().isBlank()) {
            requestSummary.set("body", boundedBody(sample.requestBody(), knownSensitiveValues));
        }
        ObjectNode responseSummary = body.putObject("responseSummary");
        responseSummary.put("statusCode", sample.responseCode());
        responseSummary.put("message", sanitizeMessage(sample.responseMessage(), knownSensitiveValues));
        responseSummary.set("headers", responseHeaders(sample.responseHeaders(), knownSensitiveValues));
        if (sample.responseBody() != null && !sample.responseBody().isBlank()) {
            responseSummary.set("body", boundedBody(sample.responseBody(), knownSensitiveValues));
        }
        ArrayNode assertions = body.putArray("assertions");
        List<String> failures = failureMessages(sample.failureMessage());
        if (plan != null && !plan.assertions().isEmpty()) {
            appendConfiguredAssertions(assertions, plan.assertions(), sample, failures, plan, knownSensitiveValues);
        } else if (sample.success()) {
            ObjectNode assertion = assertions.addObject();
            assertion.put("type", "JMeter");
            assertion.put("passed", true);
        } else if (failures.isEmpty()) {
            ObjectNode assertion = assertions.addObject();
            assertion.put("type", "JMeter");
            assertion.put("passed", false);
        } else {
            for (String failure : failures) {
                ObjectNode assertion = assertions.addObject();
                assertion.put("type", "JMeter");
                assertion.put("passed", false);
                assertion.put("message", sanitizeMessage(failure, knownSensitiveValues));
            }
        }
        ObjectNode error = body.putObject("errorSummary");
        if (!sample.success()) {
            String errorMessage = sample.failureMessage();
            if (errorMessage == null || errorMessage.isBlank()) {
                errorMessage = sample.responseMessage();
            }
            error.put("message", sanitizeMessage(errorMessage, knownSensitiveValues));
        }
        body.set("extractions", extractionArray(sample.extractionsJson(), knownSensitiveValues));
        return body;
    }

    private void appendConfiguredAssertions(ArrayNode output, List<JmeterAssertion> configured,
                                            JtlSample sample, List<String> failures, JmeterPlan plan,
                                            Set<String> knownSensitiveValues) {
        List<JtlAssertionResult> observed = sample.assertionResults();
        java.util.Set<Integer> consumed = new java.util.HashSet<>();
        for (int index = 0; index < configured.size(); index++) {
            JmeterAssertion assertion = configured.get(index);
            JtlAssertionResult result = findObserved(assertion, index, configured.size(), observed, consumed);
            boolean passed = sample.success() || result != null && result.passed();
            String message = result == null ? "" : result.message();
            String diagnostic = null;
            if (!sample.success() && result == null) {
                if (index < failures.size() && !failures.get(index).isBlank()) {
                    message = failures.get(index);
                }
                diagnostic = observed.isEmpty()
                        ? "JTL 未提供逐项 assertionResult；依据样本失败状态标记（diagnostic）"
                        : "JTL 未返回该配置断言结果（diagnostic）";
            }
            JsonNode actual = actualValue(assertion, sample, plan, knownSensitiveValues);
            if (actual == null) {
                diagnostic = diagnostic == null
                        ? "actual 无法从 JTL 证据推导（diagnostic）" : diagnostic + "；actual 无法推导";
            }
            appendAssertion(output, index, assertion, passed, actual, message, diagnostic, knownSensitiveValues);
        }
    }

    private void appendAssertion(ArrayNode output, int ruleIndex, JmeterAssertion configured, boolean passed,
                                 JsonNode actual, String message, String diagnostic,
                                 Set<String> knownSensitiveValues) {
        ObjectNode assertion = output.addObject();
        assertion.put("ruleIndex", ruleIndex);
        assertion.put("type", configured.type());
        assertion.put("operator", configured.operator());
        if (configured.expression() != null && !configured.expression().isBlank()) {
            assertion.put("expression", configured.expression());
        }
        if (configured.expected() != null) {
            assertion.set("expected", sanitizeEvidenceNode(configured.expected(), null, knownSensitiveValues));
        }
        if (actual == null) assertion.putNull("actual");
        else assertion.set("actual", actual.deepCopy());
        assertion.put("passed", passed);
        if (message != null && !message.isBlank()) {
            assertion.put("message", sanitizeMessage(message, knownSensitiveValues));
        }
        if (diagnostic != null && !diagnostic.isBlank()) {
            assertion.put("diagnostic", sanitizeMessage(diagnostic, knownSensitiveValues));
        }
    }

    private JtlAssertionResult findObserved(JmeterAssertion configured, int index, int configuredCount,
                                            List<JtlAssertionResult> observed,
                                            java.util.Set<Integer> consumed) {
        for (int resultIndex = 0; resultIndex < observed.size(); resultIndex++) {
            if (!consumed.contains(resultIndex)
                    && assertionNameMatches(configured.type(), index, observed.get(resultIndex).name())) {
                consumed.add(resultIndex);
                return observed.get(resultIndex);
            }
        }
        if (observed.size() == configuredCount && index < observed.size()
                && consumed.add(index)) {
            return observed.get(index);
        }
        return null;
    }

    private static boolean assertionNameMatches(String type, int ruleIndex, String name) {
        Matcher indexMatcher = RULE_INDEX.matcher(name == null ? "" : name);
        if (indexMatcher.find() && Integer.parseInt(indexMatcher.group(1)) != ruleIndex) {
            return false;
        }
        String normalized = (name == null ? "" : name).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return switch (type) {
            case "STATUS" -> normalized.contains("statusassertion");
            case "JSON_PATH" -> normalized.contains("jsonpathassertion");
            case "JMES_PATH" -> normalized.contains("jmespathassertion");
            case "XPATH" -> normalized.contains("xpathassertion");
            case "BODY" -> normalized.contains("bodyassertion");
            case "HEADER" -> normalized.contains("headerassertion");
            case "COOKIE" -> normalized.contains("cookieassertion");
            case "SCHEMA" -> normalized.contains("jsonschemaassertion");
            case "RESPONSE_TIME" -> normalized.contains("responsetimeassertion");
            case "VARIABLE" -> normalized.contains("variableassertion");
            default -> false;
        };
    }

    private JsonNode actualValue(JmeterAssertion configured, JtlSample sample, JmeterPlan plan,
                                 Set<String> knownSensitiveValues) {
        return switch (configured.type()) {
            case "STATUS" -> json.getNodeFactory().numberNode(sample.responseCode());
            case "RESPONSE_TIME" -> json.getNodeFactory().numberNode(sample.elapsedMs());
            case "BODY", "SCHEMA" -> sample.responseBody() == null || sample.responseBody().isBlank()
                    ? null : boundedBody(sample.responseBody(), knownSensitiveValues);
            case "JSON_PATH" -> sanitizeAssertionActual(
                    jsonPathValue(sample.responseBody(), configured.expression()), configured.expression(),
                    knownSensitiveValues);
            case "JMES_PATH" -> sanitizeAssertionActual(
                    jmesPathValue(sample.responseBody(), configured.expression()), configured.expression(),
                    knownSensitiveValues);
            case "HEADER" -> headerValue(sample.responseHeaders(), configured.expression(), knownSensitiveValues);
            case "COOKIE" -> cookieValue(sample.responseHeaders(), configured.expression(), knownSensitiveValues);
            case "VARIABLE" -> variableValue(plan, configured.expression(), knownSensitiveValues);
            default -> null;
        };
    }

    private JsonNode sanitizeAssertionActual(JsonNode actual, String expression, Set<String> knownSensitiveValues) {
        if (actual == null) return null;
        if (sensitiveExpression(expression)) return json.getNodeFactory().textNode("***");
        return sanitizeEvidenceNode(actual, null, knownSensitiveValues);
    }

    private JsonNode variableValue(JmeterPlan plan, String name, Set<String> knownSensitiveValues) {
        if (plan == null || name == null || name.isBlank() || !plan.variables().containsKey(name)) return null;
        String value = plan.variables().get(name);
        if (sensitiveKey(name) || knownSensitiveValues.contains(value)) {
            return json.getNodeFactory().textNode("***");
        }
        return json.getNodeFactory().textNode(sanitizeMessage(value, knownSensitiveValues));
    }

    private static boolean sensitiveExpression(String expression) {
        if (expression == null || expression.isBlank()) return false;
        for (String candidate : expression.split("[^A-Za-z0-9_-]+")) {
            if (sensitiveKey(candidate)) return true;
        }
        return false;
    }

    private static boolean sensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return SENSITIVE_KEYS.stream().anyMatch(item -> normalized.equals(item) || normalized.startsWith(item));
    }

    private JsonNode jsonPathValue(String body, String expression) {
        if (body == null || body.isBlank() || expression == null || expression.isBlank()) return null;
        if ("$".equals(expression)) {
            try {
                return json.readTree(body);
            } catch (IOException ignored) {
                return null;
            }
        }
        if (!expression.startsWith("$.")) return null;
        return pathValue(body, expression.substring(2));
    }

    private JsonNode jmesPathValue(String body, String expression) {
        if (body == null || body.isBlank() || expression == null || expression.isBlank()) return null;
        return pathValue(body, expression.startsWith(".") ? expression.substring(1) : expression);
    }

    private JsonNode pathValue(String body, String path) {
        try {
            JsonNode current = json.readTree(body);
            if (path.isBlank()) return current;
            for (String part : path.split("\\.")) {
                if (part.isBlank()) return null;
                int bracket = part.indexOf('[');
                String field = bracket < 0 ? part : part.substring(0, bracket);
                if (!field.isBlank()) current = current == null ? null : current.get(field);
                if (current == null) return null;
                if (bracket >= 0) {
                    int end = part.indexOf(']', bracket);
                    if (end < 0) return null;
                    int arrayIndex = Integer.parseInt(part.substring(bracket + 1, end));
                    current = current.isArray() && arrayIndex >= 0 && arrayIndex < current.size()
                            ? current.get(arrayIndex) : null;
                }
                if (current == null) return null;
            }
            return current;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Set<String> knownSensitiveValues(JmeterPlan plan, JtlSample sample) {
        Set<String> values = new LinkedHashSet<>();
        if (plan != null) {
            plan.headers().forEach(parameter -> {
                if (parameter.enabled() && sensitiveKey(parameter.name())) addKnownValue(values, parameter.value());
            });
            plan.cookies().forEach(cookie -> {
                if (cookie.enabled()) addKnownValue(values, cookie.value());
            });
            plan.variables().forEach((name, value) -> {
                if (sensitiveKey(name)) addKnownValue(values, value);
            });
            if (plan.body() != null && !"NONE".equals(plan.body().type())) {
                collectSensitiveNode(plan.body().value(), "body", values);
            }
            plan.assertions().forEach(assertion -> {
                if (sensitiveExpression(assertion.expression())) collectSensitiveNode(assertion.expected(), "actual", values);
            });
        }
        collectSensitiveHeaders(sample.responseHeaders(), values);
        collectSensitiveText(sample.responseBody(), values);
        collectSensitiveText(sample.requestBody(), values);
        collectSensitiveText(sample.url(), values);
        return values;
    }

    private void collectSensitiveHeaders(String raw, Set<String> values) {
        if (raw == null || raw.isBlank()) return;
        for (String line : raw.split("\\R")) {
            int separator = line.indexOf(':');
            if (separator > 0 && sensitiveKey(line.substring(0, separator).trim())) {
                addKnownValue(values, line.substring(separator + 1).trim());
            }
        }
    }

    private void collectSensitiveText(String raw, Set<String> values) {
        if (raw == null || raw.isBlank()) return;
        Matcher matcher = SENSITIVE_TEXT.matcher(raw);
        while (matcher.find()) addKnownValue(values, matcher.group(2));
        try {
            JsonNode parsed = json.readTree(raw);
            if (parsed != null) collectSensitiveNode(parsed, "", values);
        } catch (IOException ignored) {
            // 非 JSON 文本仍由上面的 key=value 规则覆盖。
        }
    }

    private void collectSensitiveNode(JsonNode node, String fieldName, Set<String> values) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectSensitiveNode(entry.getValue(), entry.getKey(), values));
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectSensitiveNode(item, fieldName, values));
            return;
        }
        if (node.isTextual() && (sensitiveKey(fieldName) || "body".equalsIgnoreCase(fieldName))) {
            addKnownValue(values, node.textValue());
        }
    }

    private static void addKnownValue(Set<String> values, String value) {
        if (value == null || value.isBlank() || "***".equals(value)) return;
        values.add(value);
    }

    private static String sanitizeMessage(String value, Set<String> knownValues) {
        String sanitized = value == null ? "" : value;
        for (String item : knownValues.stream()
                .filter(valueItem -> valueItem != null && !valueItem.isBlank() && !"***".equals(valueItem))
                .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            sanitized = sanitized.replace(item, "***");
        }
        sanitized = SECRET_FUNCTION.matcher(sanitized).replaceAll("***");
        sanitized = SENSITIVE_TEXT.matcher(sanitized).replaceAll("$1***$3");
        sanitized = AUTH_VALUE.matcher(sanitized).replaceAll("$1 ***");
        return truncate(sanitized);
    }

    private ObjectNode requestHeaders(JmeterPlan plan, Set<String> knownSensitiveValues) {
        ObjectNode output = json.createObjectNode();
        if (plan == null) return output;
        for (JmeterParameter header : plan.headers()) {
            if (header.enabled()) {
                output.put(header.name(), sanitizeHeaderValue(header.name(),
                        sanitizeMessage(header.value(), knownSensitiveValues)));
            }
        }
        return output;
    }

    private ObjectNode responseHeaders(String raw, Set<String> knownSensitiveValues) {
        ObjectNode output = json.createObjectNode();
        if (raw == null || raw.isBlank()) return output;
        for (String line : raw.split("\\R")) {
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            String name = line.substring(0, separator).trim();
            if (name.isBlank() || name.equalsIgnoreCase("X-Autotest-Extractions")) continue;
            addHeader(output, name, sanitizeHeaderValue(name,
                    sanitizeMessage(line.substring(separator + 1).trim(), knownSensitiveValues)));
        }
        return output;
    }

    private JsonNode headerValue(String raw, String name, Set<String> knownSensitiveValues) {
        if (name == null || name.isBlank()) return null;
        JsonNode headers = responseHeaders(raw, knownSensitiveValues);
        var fields = headers.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    private JsonNode cookieValue(String raw, String name, Set<String> knownSensitiveValues) {
        if (raw == null || raw.isBlank() || name == null || name.isBlank()) return null;
        for (String line : raw.split("\\R")) {
            int separator = line.indexOf(':');
            if (separator <= 0 || !line.substring(0, separator).trim().equalsIgnoreCase("Set-Cookie")) continue;
            String pair = line.substring(separator + 1).trim();
            int equals = pair.indexOf('=');
            if (equals <= 0 || !pair.substring(0, equals).equals(name)) continue;
            String value = pair.substring(equals + 1);
            int attributes = value.indexOf(';');
            if (attributes >= 0) value = value.substring(0, attributes).trim();
            value = sanitizeMessage(value, knownSensitiveValues);
            return sensitiveKey(name) ? json.getNodeFactory().textNode("***")
                    : json.getNodeFactory().textNode(value);
        }
        return null;
    }

    private void addHeader(ObjectNode output, String name, String value) {
        JsonNode existing = output.get(name);
        if (existing == null) {
            output.put(name, value);
        } else if (existing.isArray()) {
            ((ArrayNode) existing).add(value);
        } else {
            ArrayNode values = json.createArrayNode();
            values.add(existing);
            values.add(value);
            output.set(name, values);
        }
    }

    private static String sanitizeHeaderValue(String name, String value) {
        String normalized = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        if (java.util.Set.of("authorization", "cookie", "cookies", "setcookie", "token", "password",
                "secret", "apikey", "xapikey", "accesstoken", "refreshtoken").contains(normalized)
                || (value != null && value.contains("__autotestSecret("))) {
            return "***";
        }
        return truncate(value);
    }

    private ArrayNode extractionArray(String value, Set<String> knownSensitiveValues) {
        try {
            var node = json.readTree(value == null ? "[]" : value);
            if (node != null && node.isArray()) {
                ArrayNode sanitized = json.createArrayNode();
                node.forEach(item -> sanitized.add(sanitizeExtraction(item, knownSensitiveValues)));
                return sanitized;
            }
        } catch (IOException ignored) {
            // 恶意或损坏的元数据按空结果处理，不阻断主要运行结果回传。
        }
        return json.createArrayNode();
    }

    private ObjectNode sanitizeExtraction(com.fasterxml.jackson.databind.JsonNode value,
                                          Set<String> knownSensitiveValues) {
        ObjectNode item = json.createObjectNode();
        if (value != null && value.isObject()) {
            String variable = value.path("variable").asText("");
            String type = value.path("type").asText("");
            String expression = value.path("expression").asText("");
            boolean matched = value.path("matched").asBoolean(false);
            boolean usedDefault = value.path("usedDefault").asBoolean(false);
            boolean sensitive = matched && !usedDefault && (sensitiveKey(variable)
                    || sensitiveHeaderOrCookieName(type, expression));
            for (String field : List.of("ruleIndex", "type", "expression", "variable", "matched",
                    "usedDefault", "value", "valueType", "failed", "errorCode", "message")) {
                if (!value.has(field)) continue;
                JsonNode fieldValue = value.get(field);
                if ("value".equals(field)) {
                    item.set(field, sanitizeExtractionValue(fieldValue, sensitive, knownSensitiveValues));
                } else if (fieldValue != null && fieldValue.isTextual()) {
                    item.put(field, sanitizeMessage(fieldValue.textValue(), knownSensitiveValues));
                } else {
                    item.set(field, fieldValue == null ? null : fieldValue.deepCopy());
                }
            }
        }
        return item;
    }

    private JsonNode sanitizeExtractionValue(JsonNode value, boolean maskAll, Set<String> knownSensitiveValues) {
        if (value == null || value.isNull()) return json.getNodeFactory().nullNode();
        if (value.isObject()) {
            ObjectNode result = json.createObjectNode();
            value.fields().forEachRemaining(entry -> result.set(entry.getKey(),
                    sanitizeExtractionValue(entry.getValue(), maskAll || sensitiveKey(entry.getKey()),
                            knownSensitiveValues)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = json.createArrayNode();
            value.forEach(item -> result.add(sanitizeExtractionValue(item, maskAll, knownSensitiveValues)));
            return result;
        }
        if (value.isTextual()) {
            String sanitized = sanitizeMessage(value.textValue(), knownSensitiveValues);
            return json.getNodeFactory().textNode(maskAll ? "***" : sanitized);
        }
        if (maskAll && value.isIntegralNumber()) return json.getNodeFactory().numberNode(0L);
        if (maskAll && value.isFloatingPointNumber()) return json.getNodeFactory().numberNode(0D);
        if (maskAll && value.isBoolean()) return json.getNodeFactory().booleanNode(false);
        return value.deepCopy();
    }

    private static boolean sensitiveHeaderOrCookieName(String type, String expression) {
        String normalizedType = type == null ? "" : type.toLowerCase(java.util.Locale.ROOT);
        if (!"header".equals(normalizedType) && !"cookie".equals(normalizedType)) return false;
        String normalizedExpression = expression == null ? "" : expression.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return sensitiveKey(expression);
    }

    private com.fasterxml.jackson.databind.JsonNode bounded(com.fasterxml.jackson.databind.JsonNode value,
                                                            Set<String> knownSensitiveValues) {
        if (value == null || value.isNull()) return json.getNodeFactory().nullNode();
        return sanitizeEvidenceNode(value, null, knownSensitiveValues);
    }

    private com.fasterxml.jackson.databind.JsonNode boundedBody(String value, Set<String> knownSensitiveValues) {
        String truncated = truncate(value);
        try {
            com.fasterxml.jackson.databind.JsonNode parsed = json.readTree(truncated);
            if (parsed != null && (parsed.isObject() || parsed.isArray())) {
                return sanitizeEvidenceNode(parsed, null, knownSensitiveValues);
            }
        } catch (IOException ignored) {
            // 非 JSON 响应按受控文本证据保存。
        }
        return json.getNodeFactory().textNode(sanitizeMessage(truncated, knownSensitiveValues));
    }

    private JsonNode sanitizeEvidenceNode(JsonNode value, String fieldName, Set<String> knownSensitiveValues) {
        if (value == null || value.isNull()) return json.getNodeFactory().nullNode();
        if (value.isObject()) {
            ObjectNode result = json.createObjectNode();
            value.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                result.set(name, sensitiveKey(name)
                        ? json.getNodeFactory().textNode("***")
                        : sanitizeEvidenceNode(entry.getValue(), name, knownSensitiveValues));
            });
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = json.createArrayNode();
            value.forEach(item -> result.add(sanitizeEvidenceNode(item, fieldName, knownSensitiveValues)));
            return result;
        }
        if (value.isTextual()) {
            return json.getNodeFactory().textNode(sanitizeMessage(value.textValue(), knownSensitiveValues));
        }
        return value.deepCopy();
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_EVIDENCE_TEXT_LENGTH) return value == null ? "" : value;
        return value.substring(0, MAX_EVIDENCE_TEXT_LENGTH - 5) + "…[截断]";
    }

    private static List<String> failureMessages(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\R+"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .limit(32)
                .toList();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
