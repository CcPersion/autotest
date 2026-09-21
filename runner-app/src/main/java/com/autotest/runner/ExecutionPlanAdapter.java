package com.autotest.runner;

import com.autotest.contracts.network.TargetAllowlist;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/** 将平台保存的执行计划转换为 Runner 的最小 JMeter 输入。 */
final class ExecutionPlanAdapter {

    private static final Pattern SECRET_REFERENCE = Pattern.compile("\\$\\{secret:[A-Za-z0-9][A-Za-z0-9._-]*}");
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "secret", "secretkey", "token", "accesstoken", "refreshtoken",
            "apikey", "authorization", "cookie", "cookies", "setcookie", "xapikey", "passwordsecretref");

    private ExecutionPlanAdapter() {
    }

    static JmeterPlan fromJson(JsonNode input) {
        return fromJson(input, null);
    }

    static JmeterPlan fromJson(JsonNode input, SecretFileMaterializer secrets) {
        return fromJson(input, secrets, Map.of());
    }

    static JmeterPlan fromJson(JsonNode input, SecretFileMaterializer secrets,
                               Map<String, JsonNode> dataRow) {
        return fromJson(input, secrets, dataRow, Map.of());
    }

    static JmeterPlan fromJson(JsonNode input, SecretFileMaterializer secrets,
                               Map<String, JsonNode> dataRow,
                               Map<String, JsonNode> extracted) {
        JsonNode source = withDataRow(input, dataRow);
        RunVariableContext context = RunVariableContext.fromPlan("plan-adapter", source);
        if (extracted != null) context.putExtractedAll(extracted);
        return fromJson(source, secrets, context);
    }

    static JmeterPlan fromJson(JsonNode input, SecretFileMaterializer secrets,
                               RunVariableContext context) {
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("执行计划必须是 JSON 对象");
        }
        JsonNode assetContent = input.path("assetContent");
        JsonNode plan = assetContent.isObject() && assetContent.has("baseUrl") ? assetContent : input;
        JsonNode policySource = input.has("targetAllowlist") || input.has("targetPolicySnapshot") ? input : plan;
        plan = resolveVariables(plan, context);
        validateTargetPolicy(policySource, plan);
        validateSensitiveValues(plan);
        JmeterClientCertificate clientCertificate = readClientCertificate(plan, secrets);
        if (plan.has("clientCertificate")) {
            ObjectNode withoutCertificate = plan.deepCopy();
            withoutCertificate.remove("clientCertificate");
            plan = withoutCertificate;
        }
        if (secrets != null) {
            plan = secrets.materialize(plan);
        }
        String planId = text(plan, "planId", "执行计划缺少 planId");
        String stepId = optionalText(plan, "stepId", planId);
        String baseUrl = text(plan, "baseUrl", "执行计划缺少 baseUrl");
        String method = text(plan, "method", "执行计划缺少 method").toUpperCase(Locale.ROOT);
        String urlTemplate = text(plan, "urlTemplate", "执行计划缺少 urlTemplate");
        return new JmeterPlan(planId, stepId, baseUrl, method, urlTemplate,
                parameters(plan.path("query")), parameters(plan.path("headers")), body(plan.path("body")),
                variables(plan.path("variables")), assertions(plan.path("assertions")),
                cookies(plan.path("cookies")), booleanValue(plan, "followRedirects", true),
                nonNegativeInt(plan, "connectTimeoutMillis", 0),
                nonNegativeInt(plan, "responseTimeoutMillis", 0), proxy(plan.path("proxy")),
                extractors(plan.path("extractors")), clientCertificate,
                targetAllowlist(policySource), targetPolicyRequired(policySource),
                booleanValue(plan, "targetDnsRequired", false));
    }

    /** 读取场景首批步骤；嵌套 HTTP 计划仍复用单接口转换器，避免放开任意 JMeter 组件。 */
    static List<ScenarioExecutionStep> scenarioSteps(JsonNode input) {
        if (input == null || !input.isObject()) {
            return List.of();
        }
        JsonNode value = input.get("scenarioSteps");
        if (value == null || value.isNull() || value.isMissingNode()) {
            return List.of();
        }
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalArgumentException("scenarioSteps 必须是非空数组");
        }
        List<ScenarioExecutionStep> result = new ArrayList<>();
        Map<String, String> kindsById = new LinkedHashMap<>();
        for (JsonNode item : value) {
            if (item != null && item.isObject()) {
                String id = optionalText(item, "stepId", null);
                if (id != null) kindsById.put(id, optionalText(item, "kind", "").toUpperCase(Locale.ROOT));
            }
        }
        for (int index = 0; index < value.size(); index++) {
            JsonNode step = value.get(index);
            if (step == null || !step.isObject()) {
                throw new IllegalArgumentException("scenarioSteps[" + index + "] 必须是对象");
            }
            String stepId = text(step, "stepId", "场景步骤缺少 stepId");
            String kind = text(step, "kind", "场景步骤缺少 kind").toUpperCase(Locale.ROOT);
            boolean enabled = step.path("enabled").asBoolean(true);
            String section = optionalText(step, "section", "MAIN");
            String strategy = optionalText(step, "failureStrategy",
                    section.equalsIgnoreCase("CLEANUP") ? "CONTINUE" : "STOP");
            long waitMillis = nonNegativeLong(step, "waitMillis", 0);
            String parentId = optionalText(step, "parentId", null);
            int position = nonNegativeInt(step, "position", index);
            String parentKind = kindsById.getOrDefault(parentId, "");
            String branch = optionalText(step, "branch", "LOOP".equals(parentKind) ? "BODY" : "THEN");
            JsonNode retry = step.get("retry");
            int maxAttempts = retry != null && retry.isObject() ? nonNegativeInt(retry, "maxAttempts", 2) : 2;
            long retryIntervalMillis = retry != null && retry.isObject() ? nonNegativeLong(retry, "intervalMillis", 0) : 0;
            JsonNode plan = step.get("plan");
            if ("CONDITION".equals(kind) || "LOOP".equals(kind)) {
                plan = plan == null ? step : plan;
            }
            plan = inheritTargetAllowlist(input, plan);
            result.add(new ScenarioExecutionStep(stepId, kind, enabled, section, strategy,
                    plan, waitMillis, parentId, position, branch, maxAttempts, retryIntervalMillis));
        }
        return List.copyOf(result);
    }

    static List<SuiteExecutionStep> suiteSteps(JsonNode input) {
        if (input == null || !input.isObject()) return List.of();
        JsonNode value = input.get("suiteSteps");
        if (value == null || value.isNull() || value.isMissingNode()) return List.of();
        if (!value.isArray() || value.isEmpty()) throw new IllegalArgumentException("suiteSteps 必须是非空数组");
        List<SuiteExecutionStep> result = new ArrayList<>();
        for (int index = 0; index < value.size(); index++) {
            JsonNode item = value.get(index);
            if (item == null || !item.isObject()) throw new IllegalArgumentException("suiteSteps[" + index + "] 必须是对象");
            JsonNode memberPlan = inheritTargetAllowlist(input, item.get("plan"));
            result.add(new SuiteExecutionStep(
                    text(item, "memberId", "测试集合成员缺少 memberId"),
                    nonNegativeInt(item, "position", index),
                    text(item, "targetType", "测试集合成员缺少 targetType"),
                    text(item, "targetId", "测试集合成员缺少 targetId"),
                    item.path("enabled").asBoolean(true), memberPlan));
        }
        result.sort(Comparator.comparingInt(SuiteExecutionStep::position).thenComparing(SuiteExecutionStep::memberId));
        return List.copyOf(result);
    }

    /** 为集合中的场景步骤建立成员作用域，避免不同场景使用相同 stepId 时互相覆盖报告结果。 */
    static JsonNode prefixMemberStepIds(JsonNode input, String memberId) {
        if (input == null || !input.isObject()) return input;
        if (memberId == null || memberId.isBlank()) throw new IllegalArgumentException("测试集合成员缺少 memberId");
        ObjectNode copy = input.deepCopy();
        JsonNode value = copy.get("scenarioSteps");
        if (value == null || !value.isArray()) return copy;
        for (JsonNode rawStep : value) {
            if (!(rawStep instanceof ObjectNode step)) continue;
            String originalId = optionalText(step, "stepId", null);
            if (originalId == null || originalId.isBlank()) continue;
            String scopedId = memberId + "/" + originalId;
            step.put("stepId", scopedId);
            String parentId = optionalText(step, "parentId", null);
            if (parentId != null && !parentId.isBlank()) step.put("parentId", memberId + "/" + parentId);
            JsonNode nestedPlan = step.get("plan");
            if (nestedPlan instanceof ObjectNode plan) plan.put("stepId", scopedId);
        }
        return copy;
    }

    private static JsonNode inheritTargetAllowlist(JsonNode root, JsonNode nested) {
        if (!(nested instanceof ObjectNode child)
                || (!root.has("targetAllowlist") && !root.has("targetPolicySnapshot"))
                || child.has("targetAllowlist") || child.has("targetPolicySnapshot")) {
            return nested;
        }
        ObjectNode copy = child.deepCopy();
        if (root.has("targetAllowlist")) copy.set("targetAllowlist", root.get("targetAllowlist").deepCopy());
        if (root.has("targetPolicySnapshot")) {
            copy.set("targetPolicySnapshot", root.get("targetPolicySnapshot").deepCopy());
        }
        if (root.has("targetPolicyRequired")) {
            copy.set("targetPolicyRequired", root.get("targetPolicyRequired").deepCopy());
        }
        return copy;
    }

    private static void validateTargetPolicy(JsonNode policySource, JsonNode plan) {
        JsonNode raw = policyRulesNode(policySource);
        if (raw == null || raw.isNull()) {
            if (policySource != null && policySource.path("targetPolicyRequired").asBoolean(false)) {
                throw new IllegalArgumentException("执行计划缺少项目目标白名单");
            }
            return;
        }
        if (!raw.isArray()) {
            throw new IllegalArgumentException("targetPolicySnapshot.rules 必须是数组");
        }
        List<String> rules = new ArrayList<>();
        raw.forEach(item -> {
            if (!item.isTextual()) throw new IllegalArgumentException("targetPolicySnapshot.rules 规则必须是字符串");
            rules.add(item.textValue());
        });
        TargetAllowlist allowlist = TargetAllowlist.parse(rules);
        for (String field : List.of("baseUrl", "urlTemplate")) {
            JsonNode value = plan.get(field);
            if (value != null && value.isTextual()) {
                String text = value.textValue().strip();
                String lower = text.toLowerCase(Locale.ROOT);
                if (lower.startsWith("http://") || lower.startsWith("https://")) {
                    try {
                        TargetAllowlist.Decision decision = allowlist.evaluate(new URI(text));
                        if (!decision.allowed()) {
                            throw new IllegalArgumentException("HTTP 目标不在项目白名单中: " + decision.host());
                        }
                    } catch (URISyntaxException exception) {
                        throw new IllegalArgumentException("HTTP 目标 URL 不合法", exception);
                    }
                }
            }
        }
    }

    private static List<String> targetAllowlist(JsonNode policySource) {
        JsonNode raw = policyRulesNode(policySource);
        if (raw == null || raw.isNull()) return List.of();
        List<String> rules = new ArrayList<>();
        raw.forEach(item -> rules.add(item.asText()));
        return TargetAllowlist.parse(rules).rules();
    }

    private static boolean targetPolicyRequired(JsonNode policySource) {
        if (policySource == null) return false;
        JsonNode raw = policyRulesNode(policySource);
        return policySource.path("targetPolicyRequired").asBoolean(raw != null && !raw.isNull());
    }

    private static JsonNode policyRulesNode(JsonNode policySource) {
        if (policySource == null) return null;
        JsonNode direct = policySource.get("targetAllowlist");
        if (direct != null && !direct.isNull()) return direct;
        JsonNode snapshot = policySource.get("targetPolicySnapshot");
        return snapshot != null && snapshot.isObject() ? snapshot.get("rules") : null;
    }

    static List<DataRow> dataRows(JsonNode input) {
        if (input == null || !input.isObject()) return List.of();
        JsonNode assetContent = input.path("assetContent");
        JsonNode plan = assetContent.isObject() && assetContent.has("baseUrl") ? assetContent : input;
        JsonNode rows = plan.get("dataRows");
        if (rows == null || rows.isNull() || rows.isMissingNode()) return List.of();
        if (!rows.isArray()) throw new IllegalArgumentException("dataRows 必须是数组");
        List<DataRow> result = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            if (row == null || !row.isObject()) {
                throw new IllegalArgumentException("dataRows[" + index + "] 必须是对象");
            }
            String id = row.path("id").asText("");
            if (id.isBlank() || id.length() > 128) {
                throw new IllegalArgumentException("dataRows[" + index + "].id 不合法");
            }
            JsonNode values = row.get("values");
            if (values == null || !values.isObject()) {
                throw new IllegalArgumentException("dataRows[" + index + "].values 必须是对象");
            }
            Map<String, JsonNode> rowValues = new LinkedHashMap<>();
            values.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value != null && !(value.isValueNode())) {
                    throw new IllegalArgumentException("数据行值必须是标量: " + entry.getKey());
                }
                rowValues.put(entry.getKey(), value == null ? null : value.deepCopy());
            });
            result.add(new DataRow(id, row.path("enabled").asBoolean(true), rowValues));
        }
        return List.copyOf(result);
    }

    private static JsonNode withDataRow(JsonNode plan, Map<String, JsonNode> dataRow) {
        if (dataRow == null || dataRow.isEmpty()) return plan;
        ObjectNode copy = plan.deepCopy();
        ObjectNode scopes;
        JsonNode existing = copy.get("variableScopes");
        if (existing == null || existing.isNull()) {
            scopes = copy.putObject("variableScopes");
        } else if (existing.isObject()) {
            scopes = (ObjectNode) existing;
        } else {
            throw new IllegalArgumentException("variableScopes 必须是对象");
        }
        ObjectNode row = scopes.putObject("dataRow");
        dataRow.forEach((name, value) -> row.set(name, value == null ? null : value.deepCopy()));
        return copy;
    }

    private static JmeterClientCertificate readClientCertificate(JsonNode plan,
                                                                SecretFileMaterializer secrets) {
        JsonNode value = plan.get("clientCertificate");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("clientCertificate 必须是对象");
        }
        String type = optionalText(value, "type", "").toUpperCase(Locale.ROOT);
        if (!type.equals("PKCS12")) {
            throw new IllegalArgumentException("clientCertificate.type 只支持 PKCS12");
        }
        String certificateRef = optionalText(value, "secretRef", null);
        String passwordRef = optionalText(value, "passwordRef", optionalText(value, "passwordSecretRef", null));
        if (secrets == null) {
            throw new IllegalArgumentException("包含 PKCS12 证书的运行缺少安全解析上下文");
        }
        Path certificateFile;
        String filePath = optionalText(value, "filePath", null);
        if (filePath != null && !filePath.isBlank()) {
            certificateFile = Path.of(filePath).toAbsolutePath().normalize();
            if (!java.nio.file.Files.isRegularFile(certificateFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("证书文件不存在");
            }
        } else {
            if (!isSecretReference(certificateRef)) {
                throw new IllegalArgumentException("PKCS12 证书必须使用运行文件快照或密钥引用");
            }
            certificateFile = secrets.materializePkcs12(certificateRef);
        }
        if (!isSecretReference(passwordRef)) {
            throw new IllegalArgumentException("PKCS12 密码必须使用密钥引用");
        }
        String password = secrets.resolveText(passwordRef);
        return new JmeterClientCertificate(certificateFile, password);
    }

    private static JsonNode resolveVariables(JsonNode plan, Map<String, JsonNode> extractedOverride) {
        RunVariableContext context = RunVariableContext.fromPlan("plan-adapter", plan);
        if (extractedOverride != null) context.putExtractedAll(extractedOverride);
        return resolveVariables(plan, context);
    }

    private static JsonNode resolveVariables(JsonNode plan, RunVariableContext context) {
        RunVariableContext.PreflightResult preflight = context.preflight(plan, "executionPlan");
        if (!preflight.valid()) {
            throw new IllegalArgumentException(preflight.code() + ": " + preflight.path());
        }
        return context.resolveNode(plan);
    }

    private static Map<String, JsonNode> scope(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return Map.of();
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException(field + " 必须是对象");
        }
        Map<String, JsonNode> result = new LinkedHashMap<>();
        value.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private static List<JmeterParameter> parameters(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("query/headers 必须是数组");
        }
        List<JmeterParameter> parameters = new ArrayList<>();
        for (JsonNode item : value) {
            parameters.add(new JmeterParameter(text(item, "name", "参数缺少 name"),
                    text(item, "value", "参数缺少 value"), item.path("enabled").asBoolean(true)));
        }
        return parameters;
    }

    private static JmeterBody body(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return JmeterBody.none();
        }
        String type = text(value, "type", "body 缺少 type").toUpperCase(Locale.ROOT);
        return type.equals("NONE") ? JmeterBody.none() : new JmeterBody(type, value.get("value"));
    }

    private static List<JmeterCookie> cookies(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("cookies 必须是数组");
        }
        List<JmeterCookie> cookies = new ArrayList<>();
        for (JsonNode item : value) {
            cookies.add(new JmeterCookie(text(item, "name", "Cookie 缺少 name"),
                    text(item, "value", "Cookie 缺少 value"), optionalText(item, "domain", ""),
                    optionalText(item, "path", "/"), item.path("secure").asBoolean(false),
                    item.path("enabled").asBoolean(true)));
        }
        return cookies;
    }

    private static JmeterProxy proxy(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("proxy 必须是对象");
        }
        String host = text(value, "host", "proxy 缺少 host");
        int port = nonNegativeInt(value, "port", -1);
        if (port < 1) {
            throw new IllegalArgumentException("proxy.port 必须是正整数");
        }
        return new JmeterProxy(optionalText(value, "scheme", "http"), host, port,
                optionalText(value, "username", ""), optionalText(value, "password", ""));
    }

    private static boolean booleanValue(JsonNode node, String field, boolean fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asBoolean();
    }

    private static int nonNegativeInt(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.canConvertToInt() || value.asInt() < 0) {
            throw new IllegalArgumentException(field + " 必须是非负整数");
        }
        return value.asInt();
    }

    private static long nonNegativeLong(JsonNode node, String field, long fallback) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.canConvertToLong() || value.asLong() < 0) {
            throw new IllegalArgumentException(field + " 必须是非负整数");
        }
        return value.asLong();
    }

    private static Map<String, String> variables(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return Map.of();
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("variables 必须是对象");
        }
        Map<String, String> variables = new LinkedHashMap<>();
        value.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNull()) {
                throw new IllegalArgumentException("变量值不能为 null");
            }
            variables.put(entry.getKey(), entry.getValue().isValueNode()
                    ? entry.getValue().asText() : entry.getValue().toString());
        });
        return variables;
    }

    private static List<JmeterAssertion> assertions(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("assertions 必须是数组");
        }
        List<JmeterAssertion> assertions = new ArrayList<>();
        for (JsonNode item : value) {
            assertions.add(new JmeterAssertion(optionalText(item, "type", ""),
                    optionalText(item, "operator", ""), optionalText(item, "expression", null),
                    item.get("expected")));
        }
        return assertions;
    }

    private static List<JmeterExtractor> extractors(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("extractors 必须是数组");
        }
        List<JmeterExtractor> extractors = new ArrayList<>();
        for (JsonNode item : value) {
            JsonNode defaultNode = item.get("defaultValue");
            String defaultValue = defaultNode == null || defaultNode.isNull()
                    ? null : defaultNode.isTextual() ? defaultNode.textValue() : defaultNode.toString();
            extractors.add(new JmeterExtractor(optionalText(item, "type", ""),
                    text(item, "expression", "提取器缺少 expression"),
                    text(item, "variable", "提取器缺少 variable"),
                    defaultValue, item.path("failIfMissing").asBoolean(true), item.has("defaultValue")));
        }
        return extractors;
    }

    private static String text(JsonNode node, String field, String message) {
        String value = optionalText(node, field, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.isTextual() ? value.textValue() : value.toString();
    }

    private static boolean isSecretReference(String value) {
        return value != null && SECRET_REFERENCE.matcher(value).matches();
    }

    private static void validateSensitiveValues(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                boolean structuralSensitiveContainer = (key.equals("cookies") || key.equals("secretrefs"))
                        && (field.getValue().isArray() || field.getValue().isObject());
                if (SENSITIVE_KEYS.contains(key) && !structuralSensitiveContainer
                        && !safeSecretValue(field.getValue())) {
                    throw new IllegalArgumentException("敏感字段只能使用 ${secret:name} 引用");
                }
                if (key.equals("headers") || key.equals("cookies")) {
                    validateNamedSecrets(field.getValue(), key.equals("cookies"));
                }
                validateSensitiveValues(field.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(ExecutionPlanAdapter::validateSensitiveValues);
        }
    }

    private static boolean safeSecretValue(JsonNode value) {
        if (!value.isTextual()) {
            return false;
        }
        String text = value.textValue();
        return SECRET_REFERENCE.matcher(text).matches()
                || text.matches("(?:Bearer|Basic) \\$\\{secret:[A-Za-z0-9][A-Za-z0-9._-]*}");
    }

    private static void validateNamedSecrets(JsonNode value, boolean everyCookieIsSecret) {
        if (!value.isArray()) {
            return;
        }
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("headers/cookies 项必须是对象");
            }
            String name = optionalText(item, "name", "").toLowerCase(Locale.ROOT)
                    .replace("_", "").replace("-", "");
            if (everyCookieIsSecret || SENSITIVE_KEYS.contains(name)) {
                JsonNode secret = item.get("value");
                if (!safeSecretValue(secret)) {
                    throw new IllegalArgumentException("敏感字段只能使用 ${secret:name} 引用");
                }
            }
        }
    }
}
