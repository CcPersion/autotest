package com.autotest.platform.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 只读取 OpenAPI 3 文档，不解析远程引用、不发起网络请求。 */
public final class OpenApiImportParser {
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete", "head", "options");
    private static final Set<String> SENSITIVE = Set.of("authorization", "cookie", "set-cookie", "x-api-key", "api-key");
    private final ObjectMapper mapper;
    private final ObjectMapper yamlMapper;

    public OpenApiImportParser(ObjectMapper mapper) {
        this.mapper = mapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    public ImportDocument parse(String source) {
        if (source == null || source.isBlank()) throw error("$", "OpenAPI 内容不能为空");
        JsonNode root;
        try {
            String trimmed = source.stripLeading();
            root = (trimmed.startsWith("{") || trimmed.startsWith("["))
                    ? mapper.readTree(source) : yamlMapper.readTree(source);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw error("$", "OpenAPI 文档格式不正确");
        }
        rejectRemoteRefs(root, "$", new LinkedHashSet<>());
        if (root == null || !root.isObject() || !root.path("openapi").asText("").startsWith("3.")) {
            throw error("openapi", "只支持 OpenAPI 3.x 文档");
        }
        JsonNode paths = root.get("paths");
        if (paths == null || !paths.isObject()) throw error("paths", "paths 必须是对象");
        String server = server(root.path("servers"));
        List<ImportCandidate> candidates = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        paths.fields().forEachRemaining(pathEntry -> {
            String path = pathEntry.getKey();
            if (!path.startsWith("/")) throw error("paths." + path, "路径必须以 / 开头");
            JsonNode pathItem = pathEntry.getValue();
            if (!pathItem.isObject()) throw error("paths." + path, "路径项必须是对象");
            pathItem.fields().forEachRemaining(operation -> {
                String operationName = operation.getKey().toLowerCase(Locale.ROOT);
                if (!METHODS.contains(operationName)) {
                    if (!"summary".equals(operationName) && !"description".equals(operationName)
                            && !"parameters".equals(operationName) && !"servers".equals(operationName)) {
                        throw error("paths." + path + "." + operation.getKey(), "不支持的路径项字段");
                    }
                    return;
                }
                JsonNode op = operation.getValue();
                if (!op.isObject()) throw error("paths." + path + "." + operationName, "操作必须是对象");
                List<JsonNode> parameters = new ArrayList<>();
                addParameters(parameters, pathItem.get("parameters"), "paths." + path + ".parameters");
                addParameters(parameters, op.get("parameters"), "paths." + path + "." + operationName + ".parameters");
                ArrayNode pathParams = mapper.createArrayNode();
                ArrayNode query = mapper.createArrayNode();
                ArrayNode headers = mapper.createArrayNode();
                ArrayNode cookies = mapper.createArrayNode();
                Map<String, String> casePath = new LinkedHashMap<>();
                Map<String, String> caseQuery = new LinkedHashMap<>();
                Map<String, String> caseHeaders = new LinkedHashMap<>();
                Map<String, String> caseCookies = new LinkedHashMap<>();
                List<String> candidateWarnings = new ArrayList<>();
                for (int index = 0; index < parameters.size(); index++) {
                    JsonNode parameter = parameters.get(index);
                    String parameterPath = "paths." + path + "." + operationName + ".parameters[" + index + "]";
                    String name = text(parameter, "name", parameterPath + ".name");
                    String location = text(parameter, "in", parameterPath + ".in");
                    if (!Set.of("path", "query", "header", "cookie").contains(location)) {
                        throw error(parameterPath + ".in", "参数位置不支持");
                    }
                    String value = example(parameter.get("example"), parameter.get("schema"));
                    if (value == null) value = "${" + name + "}";
                    if (location.equals("path") && !path.contains("{" + name + "}")) {
                        throw error(parameterPath + ".name", "path 参数必须出现在路径模板中");
                    }
                    if (location.equals("path") && !parameter.path("required").asBoolean(false)) {
                        throw error(parameterPath + ".required", "path 参数必须 required=true");
                    }
                    boolean sensitive = location.equals("cookie") || (location.equals("header") && isSensitive(name));
                    if (sensitive) {
                        value = "${secret:imported_" + safeName(name) + "}";
                        candidateWarnings.add("参数 " + name + " 的值已脱敏，确认前必须绑定已有密钥");
                    }
                    ObjectNode item = entry(name, value, true);
                    switch (location) {
                        case "path" -> { pathParams.add(item); casePath.put(name, value); }
                        case "query" -> { query.add(item); caseQuery.put(name, value); }
                        case "header" -> { headers.add(item); caseHeaders.put(name, value); }
                        case "cookie" -> { cookies.add(item); caseCookies.put(name, value); }
                        default -> throw new IllegalStateException();
                    }
                }
                // OpenAPI 路径模板本身也要声明未显式列出的 path 参数，交给平台校验定位遗漏。
                Set<String> declaredPath = new LinkedHashSet<>();
                pathParams.forEach(item -> declaredPath.add(item.get("name").asText()));
                for (String placeholder : placeholders(path)) {
                    if (!declaredPath.contains(placeholder)) {
                        String value = "${" + placeholder + "}";
                        pathParams.add(entry(placeholder, value, false));
                        casePath.put(placeholder, value);
                        candidateWarnings.add("路径参数 " + placeholder + " 未在 OpenAPI parameters 中声明，已生成占位值");
                    }
                }
                String requestBodyPath = "paths." + path + "." + operationName + ".requestBody";
                if (Set.of("get", "head", "options").contains(operationName)
                        && op.has("requestBody") && !op.path("requestBody").path("content").isEmpty()) {
                    throw error(requestBodyPath, operationName.toUpperCase(Locale.ROOT) + " 不允许导入请求体");
                }
                ObjectNode body = body(op.get("requestBody"), requestBodyPath);
                ObjectNode requestSpec = mapper.createObjectNode();
                requestSpec.set("pathParams", pathParams);
                requestSpec.set("query", query);
                requestSpec.set("headers", headers);
                requestSpec.set("cookies", cookies);
                requestSpec.set("body", body);
                requestSpec.set("options", mapper.createObjectNode());
                ObjectNode caseSpec = mapper.createObjectNode();
                caseSpec.set("pathParams", mapper.valueToTree(casePath));
                caseSpec.set("query", mapper.valueToTree(caseQuery));
                caseSpec.set("headers", mapper.valueToTree(caseHeaders));
                caseSpec.set("cookies", mapper.valueToTree(caseCookies));
                caseSpec.set("body", body.deepCopy());
                caseSpec.set("extractors", mapper.createArrayNode());
                String operationId = op.path("operationId").asText("");
                String name = operationId.isBlank() ? operationName.toUpperCase(Locale.ROOT) + " " + path : operationId;
                String url = join(server, path);
                ImportCandidate candidate = new ImportCandidate("paths." + path + "." + operationName, name,
                        name + " 默认用例", operationName.toUpperCase(Locale.ROOT), url, requestSpec, caseSpec,
                        mapper.createObjectNode(), mapper.createArrayNode(), candidateWarnings);
                candidates.add(candidate);
                warnings.addAll(candidateWarnings);
            });
        });
        if (candidates.isEmpty()) throw error("paths", "OpenAPI 文档没有可导入的操作");
        return new ImportDocument("OPENAPI", candidates, warnings);
    }

    private ObjectNode body(JsonNode requestBody, String path) {
        ObjectNode none = mapper.createObjectNode();
        none.put("type", "NONE");
        if (requestBody == null || requestBody.isNull()) return none;
        if (!requestBody.isObject()) throw error(path, "requestBody 必须是对象");
        JsonNode content = requestBody.get("content");
        if (content == null || !content.isObject() || content.size() == 0) return none;
        String media = content.fieldNames().next();
        JsonNode mediaType = content.get(media);
        String example = example(mediaType == null ? null : mediaType.get("example"),
                mediaType == null ? null : mediaType.get("schema"));
        ObjectNode body = mapper.createObjectNode();
        if (media.contains("json") || media.endsWith("+json")) {
            body.put("type", "JSON");
            if (example == null) body.set("value", mapper.createObjectNode());
            else {
                try {
                    body.set("value", mapper.readTree(example));
                } catch (JsonProcessingException exception) {
                    throw error(path + ".content." + media, "JSON 示例格式不正确");
                }
            }
            return body;
        }
        if (media.equals("text/plain")) {
            body.put("type", "TEXT");
            body.put("value", example == null ? "" : example);
            return body;
        }
        if (media.equals("application/x-www-form-urlencoded")) {
            body.put("type", "URLENCODED");
            ArrayNode values = mapper.createArrayNode();
            JsonNode schema = mediaType == null ? null : mediaType.get("schema");
            if (schema != null && schema.path("properties").isObject()) {
                schema.path("properties").fieldNames().forEachRemaining(name -> values.add(entry(name, "${" + name + "}", true)));
            }
            body.set("value", values);
            return body;
        }
        throw error(path + ".content." + media, "请求体类型不支持");
    }

    private void addParameters(List<JsonNode> target, JsonNode node, String path) {
        if (node == null || node.isNull()) return;
        if (!node.isArray()) throw error(path, "parameters 必须是数组");
        node.forEach(target::add);
    }

    private String server(JsonNode servers) {
        if (servers == null || !servers.isArray() || servers.isEmpty()) return "";
        JsonNode server = servers.get(0);
        String value = server.path("url").asText("");
        if (value.isBlank()) throw error("servers[0].url", "server URL 不能为空");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{([^{}]+)}").matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            JsonNode variable = server.path("variables").path(matcher.group(1));
            String replacement = variable.path("default").asText("");
            if (replacement.isBlank()) throw error("servers[0].variables." + matcher.group(1), "server 变量必须提供 default");
            matcher.appendReplacement(resolved, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        value = resolved.toString();
        if (!value.startsWith("http://") && !value.startsWith("https://") && !value.startsWith("/")) {
            throw error("servers[0].url", "只支持 HTTP/HTTPS server URL");
        }
        return value;
    }

    private String join(String server, String path) {
        if (server.isBlank()) return path;
        return server.endsWith("/") && path.startsWith("/") ? server.substring(0, server.length() - 1) + path : server + path;
    }

    private String text(JsonNode object, String field, String path) {
        String value = object.path(field).asText("");
        if (value.isBlank()) throw error(path, field + " 不能为空");
        return value;
    }

    private String example(JsonNode example, JsonNode schema) {
        if (example != null && !example.isNull() && !example.isMissingNode()) return scalar(example);
        if (schema == null || schema.isNull()) return null;
        JsonNode nested = schema.get("example");
        if (nested != null) return scalar(nested);
        JsonNode defaultValue = schema.get("default");
        return defaultValue == null ? null : scalar(defaultValue);
    }

    private String scalar(JsonNode value) {
        return value.isTextual() ? value.asText() : value.toString();
    }

    private ArrayList<String> placeholders(String path) {
        ArrayList<String> result = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{([^{}]+)}").matcher(path);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private ObjectNode entry(String name, String value, boolean enabled) {
        ObjectNode node = mapper.createObjectNode();
        node.put("name", name);
        node.put("value", value);
        if (enabled) node.put("enabled", true);
        return node;
    }

    private boolean isSensitive(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replace('_', '-');
        return SENSITIVE.contains(normalized) || normalized.endsWith("-token") || normalized.endsWith("-key");
    }

    private String safeName(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private void rejectRemoteRefs(JsonNode node, String path, Set<JsonNode> seen) {
        if (node == null || !seen.add(node)) return;
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && !ref.asText("").startsWith("#")) throw error(path + ".$ref", "禁止使用远程引用");
            node.fields().forEachRemaining(entry -> rejectRemoteRefs(entry.getValue(), path + "." + entry.getKey(), seen));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) rejectRemoteRefs(node.get(index), path + "[" + index + "]", seen);
        }
    }

    private ImportParseException error(String path, String message) {
        return new ImportParseException(path, message);
    }
}
