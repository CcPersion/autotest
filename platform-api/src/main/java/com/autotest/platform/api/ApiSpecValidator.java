package com.autotest.platform.api;

import com.autotest.platform.secret.SecretRepository;
import com.autotest.platform.security.ApiDomainException;
import com.autotest.contracts.extraction.SafeRegex;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ApiSpecValidator {

    private static final Pattern SECRET_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final Pattern PATH_PLACEHOLDER = Pattern.compile("(?<!\\$)\\{([^{}]+)\\}");
    private static final Pattern TOKEN = Pattern.compile("\\$\\{([^{}]*)\\}");

    private ApiSpecValidator() {
    }

    /**
     * Execution entry points (preview/debug) use the same schema validator as
     * definition persistence.  Keeping this small public facade avoids a
     * second, weaker draft-only validator in the run package.
     */
    public static void validateDefinitionForExecution(UUID projectId, String method, String urlTemplate,
                                                       JsonNode requestSpec, SecretRepository secrets) {
        validateDefinition(projectId, method, urlTemplate, requestSpec, secrets);
    }

    record DefinitionShape(Set<String> pathNames, Set<String> queryNames, Set<String> headerNames,
                           Set<String> cookieNames, String bodyType) {
    }

    static String name(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw invalid("name", "INVALID_NAME", "名称长度必须为 1 到 256 个字符");
        }
        return normalized;
    }

    static DefinitionShape validateDefinition(UUID projectId, String method, String urlTemplate,
                                               JsonNode requestSpec, SecretRepository secrets) {
        String normalizedMethod = method == null ? "" : method.toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(normalizedMethod)) {
            throw invalid("method", "INVALID_METHOD", "method 不支持");
        }
        Set<String> placeholders = url(projectId, urlTemplate, secrets);
        if (requestSpec == null || !requestSpec.isObject()) {
            throw invalid("requestSpec", "INVALID_JSON", "requestSpec 必须是 JSON 对象");
        }
        allowed(requestSpec, Set.of("pathParams", "query", "headers", "cookies", "body", "options"), "requestSpec");
        required(requestSpec, "pathParams", "requestSpec");
        required(requestSpec, "query", "requestSpec");
        required(requestSpec, "headers", "requestSpec");
        required(requestSpec, "body", "requestSpec");
        Set<String> pathNames = entries(projectId, requestSpec.get("pathParams"), "requestSpec.pathParams",
                false, false, secrets);
        Set<String> queryNames = entries(projectId, requestSpec.get("query"), "requestSpec.query",
                false, true, secrets);
        Set<String> headerNames = entries(projectId, requestSpec.get("headers"), "requestSpec.headers",
                true, true, secrets);
        Set<String> cookieNames = entries(projectId, requestSpec.get("cookies"), "requestSpec.cookies",
                true, true, secrets);
        sensitiveEntries(requestSpec.get("headers"), "requestSpec.headers", false);
        sensitiveEntries(requestSpec.get("cookies"), "requestSpec.cookies", true);
        if (!placeholders.equals(pathNames)) {
            throw invalid("requestSpec.pathParams", "PATH_PARAMS_MISMATCH", "URL 占位符必须与 pathParams 一一对应");
        }
        String bodyType = body(projectId, requestSpec.get("body"), "requestSpec.body", normalizedMethod, secrets);
        validateOptions(projectId, requestSpec.get("options"), "requestSpec.options", secrets);
        return new DefinitionShape(Set.copyOf(pathNames), Set.copyOf(queryNames), Set.copyOf(headerNames),
                Set.copyOf(cookieNames), bodyType);
    }

    static void validateCase(UUID projectId, ApiDefinitionRecord definition, JsonNode caseSpec,
                             JsonNode variables, JsonNode assertions, SecretRepository secrets) {
        validateCase(projectId, definition, caseSpec, variables, assertions, secrets, false);
    }

    /**
     * 校验新写入时默认拒绝旧版无 expression 的 Header/Cookie 断言；父接口更新
     * 读取旧用例时可以显式开启一次兼容适配，避免历史资产无法读取。
     */
    static void validateCase(UUID projectId, ApiDefinitionRecord definition, JsonNode caseSpec,
                             JsonNode variables, JsonNode assertions, SecretRepository secrets,
                             boolean allowLegacyHeaderCookieAssertions) {
        DefinitionShape shape = validateDefinition(projectId, definition.method(), definition.urlTemplate(),
                definition.requestSpec(), secrets);
        if (caseSpec == null || !caseSpec.isObject()) {
            throw invalid("caseSpec", "INVALID_JSON", "caseSpec 必须是 JSON 对象");
        }
        allowed(caseSpec, Set.of("pathParams", "query", "headers", "cookies", "body", "options", "extractors",
                "dataRows", "dataRowOptions"), "caseSpec");
        required(caseSpec, "pathParams", "caseSpec");
        required(caseSpec, "query", "caseSpec");
        required(caseSpec, "headers", "caseSpec");
        required(caseSpec, "body", "caseSpec");
        overrides(projectId, caseSpec.get("pathParams"), "caseSpec.pathParams", shape.pathNames(), false, secrets);
        overrides(projectId, caseSpec.get("query"), "caseSpec.query", shape.queryNames(), false, secrets);
        overrides(projectId, caseSpec.get("headers"), "caseSpec.headers", shape.headerNames(), true, secrets);
        overrides(projectId, caseSpec.get("cookies"), "caseSpec.cookies", shape.cookieNames(), true, secrets);
        if (caseSpec.has("body")) {
            String overrideType = body(projectId, caseSpec.get("body"), "caseSpec.body", definition.method(), secrets);
            if (!shape.bodyType().equals(overrideType)) {
                throw invalid("caseSpec.body.type", "BODY_TYPE_OVERRIDE_FORBIDDEN",
                        "接口用例只能覆盖接口定义相同类型的 body.value");
            }
        }
        if (caseSpec.has("options")) {
            caseOptions(caseSpec.get("options"), "caseSpec.options");
        }
        extractors(projectId, caseSpec.get("extractors"), "caseSpec.extractors", secrets);
        dataRows(projectId, caseSpec.get("dataRows"), "caseSpec.dataRows", secrets);
        dataRowOptions(caseSpec.get("dataRowOptions"), "caseSpec.dataRowOptions");
        if (variables == null || !variables.isObject()) {
            throw invalid("variables", "INVALID_JSON", "variables 必须是 JSON 对象");
        }
        Iterator<String> variableNames = variables.fieldNames();
        while (variableNames.hasNext()) {
            String variableName = variableNames.next();
            if (!VARIABLE_NAME.matcher(variableName).matches() || variableName.startsWith("secret:")) {
                throw invalid("variables." + variableName, "INVALID_VARIABLE_NAME", "变量名不合法");
            }
        }
        references(projectId, variables, "variables", secrets);
        if (assertions == null || !assertions.isArray()) {
            throw invalid("assertions", "INVALID_JSON", "assertions 必须是 JSON 数组");
        }
        int index = 0;
        for (JsonNode assertion : assertions) {
            assertion(projectId, assertion, "assertions[" + index + "]", secrets,
                    allowLegacyHeaderCookieAssertions);
            index++;
        }
        references(projectId, caseSpec, "caseSpec", secrets);
    }

    private static void caseOptions(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw invalid(path, "INVALID_JSON", "用例 options 必须是对象");
        }
        allowed(node, Set.of("connectTimeoutMillis", "readTimeoutMillis", "totalTimeoutMillis",
                // Preserve the pre-F2 response timeout spelling for existing saved cases.
                "responseTimeoutMillis"), path);
        for (String field : List.of("connectTimeoutMillis", "readTimeoutMillis", "totalTimeoutMillis",
                "responseTimeoutMillis")) {
            if (node.has(field) && (!node.get(field).canConvertToInt()
                    || node.get(field).asInt() < 1 || node.get(field).asInt() > 120000)) {
                throw invalid(path + "." + field, "TIMEOUT_INVALID", field + " 必须在 1-120000 范围内");
            }
        }
    }

    private static void dataRows(UUID projectId, JsonNode node, String path, SecretRepository secrets) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (!node.isArray()) throw invalid(path, "INVALID_JSON", "dataRows 必须是 JSON 数组");
        Set<String> ids = new HashSet<>();
        int index = 0;
        for (JsonNode row : node) {
            String rowPath = path + "[" + index + "]";
            if (row == null || !row.isObject()) {
                throw invalid(rowPath, "INVALID_JSON", "数据行必须是 JSON 对象");
            }
            allowed(row, Set.of("id", "enabled", "values"), rowPath);
            String id = requiredText(row.get("id"), rowPath + ".id", "数据行 id 不能为空");
            if (!ids.add(id)) {
                throw invalid(rowPath + ".id", "DUPLICATE_DATA_ROW", "数据行 id 不能重复");
            }
            enabled(row.get("enabled"), rowPath + ".enabled");
            JsonNode values = row.get("values");
            if (values == null || !values.isObject()) {
                throw invalid(rowPath + ".values", "INVALID_JSON", "数据行 values 必须是 JSON 对象");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey();
                if (!VARIABLE_NAME.matcher(name).matches() || name.startsWith("secret:")) {
                    throw invalid(rowPath + ".values." + name, "INVALID_VARIABLE_NAME", "数据行变量名不合法");
                }
                JsonNode value = field.getValue();
                if (value != null && !(value.isTextual() || value.isNumber() || value.isBoolean() || value.isNull())) {
                    throw invalid(rowPath + ".values." + name, "INVALID_DATA_ROW_VALUE",
                            "数据行值必须是字符串、数字、布尔或 null");
                }
                references(projectId, value, rowPath + ".values." + name, secrets);
            }
            index++;
        }
    }

    private static void dataRowOptions(JsonNode node, String path) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (!node.isObject()) throw invalid(path, "INVALID_JSON", "dataRowOptions 必须是 JSON 对象");
        allowed(node, Set.of("continueOnFailure"), path);
        if (node.has("continueOnFailure") && !node.get("continueOnFailure").isBoolean()) {
            throw invalid(path + ".continueOnFailure", "INVALID_BOOLEAN", "continueOnFailure 必须是 boolean");
        }
    }

    private static Set<String> url(UUID projectId, String value, SecretRepository secrets) {
        String url = value == null ? "" : value;
        if (url.isEmpty() || hasWhitespaceOrControl(url)) {
            throw invalid("urlTemplate", "INVALID_URL", "URL 不合法");
        }
        references(projectId, textNode(url), "urlTemplate", secrets);
        String sanitized = TOKEN.matcher(url).replaceAll("x");
        Matcher pathMatcher = PATH_PLACEHOLDER.matcher(sanitized);
        Set<String> placeholders = new LinkedHashSet<>();
        while (pathMatcher.find()) {
            String name = pathMatcher.group(1);
            if (!VARIABLE_NAME.matcher(name).matches() || !placeholders.add(name)) {
                throw invalid("urlTemplate", "INVALID_PLACEHOLDER", "URL 占位符不合法或重复");
            }
        }
        String withoutPlaceholders = pathMatcher.reset().replaceAll("x");
        if (withoutPlaceholders.indexOf('{') >= 0 || withoutPlaceholders.indexOf('}') >= 0) {
            throw invalid("urlTemplate", "INVALID_PLACEHOLDER", "URL 占位符不合法");
        }
        try {
            URI uri = new URI(withoutPlaceholders);
            boolean relative = url.startsWith("/") && !url.startsWith("//") && !uri.isAbsolute()
                    && uri.getRawAuthority() == null;
            if (relative) {
                return placeholders;
            }
            if (!uri.isAbsolute() || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return placeholders;
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw invalid("urlTemplate", "INVALID_URL", "URL 不合法");
        }
    }

    private static JsonNode textNode(String value) {
        return new com.fasterxml.jackson.databind.node.TextNode(value);
    }

    private static Set<String> entries(UUID projectId, JsonNode node, String path, boolean caseInsensitive,
                                       boolean enabledRequired, SecretRepository secrets) {
        Set<String> names = new LinkedHashSet<>();
        if (node == null) {
            return names;
        }
        if (!node.isArray()) {
            throw invalid(path, "INVALID_JSON", "参数列表必须是 JSON 数组");
        }
        int index = 0;
        for (JsonNode item : node) {
            String itemPath = path + "[" + index + "]";
            if (item == null || !item.isObject()) {
                throw invalid(itemPath, "INVALID_JSON", "参数项必须是 JSON 对象");
            }
            allowed(item, enabledRequired ? Set.of("name", "value", "enabled") : Set.of("name", "value"), itemPath);
            String name = requiredText(item.get("name"), itemPath + ".name", "参数名不能为空");
            if (hasWhitespaceOrControl(name)) {
                throw invalid(itemPath + ".name", "INVALID_STRING", "参数名不能包含空白或控制字符");
            }
            requiredText(item.get("value"), itemPath + ".value", "参数值必须是字符串");
            if (enabledRequired && item.get("enabled") == null) {
                throw invalid(itemPath + ".enabled", "INVALID_BOOLEAN", "enabled 必须是 boolean");
            }
            enabled(item.get("enabled"), itemPath + ".enabled");
            String duplicateKey = caseInsensitive ? name.toLowerCase() : name;
            if (!names.stream().map(existing -> caseInsensitive ? existing.toLowerCase() : existing)
                    .noneMatch(duplicateKey::equals)) {
                throw invalid(itemPath + ".name", "DUPLICATE_PARAMETER", "参数名不能重复");
            }
            names.add(name);
            references(projectId, item.get("value"), itemPath + ".value", secrets);
            index++;
        }
        return names;
    }

    private static void overrides(UUID projectId, JsonNode node, String path, Set<String> declared,
                                  boolean caseInsensitive, SecretRepository secrets) {
        if (node == null) {
            return;
        }
        if (!node.isObject()) {
            throw invalid(path, "INVALID_JSON", "覆盖值必须是 JSON 对象");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            boolean declaredKey = caseInsensitive
                    ? declared.stream().anyMatch(item -> item.equalsIgnoreCase(key))
                    : declared.contains(key);
            if (!declaredKey) {
                throw invalid(path + "." + key, "UNDECLARED_OVERRIDE", "覆盖值必须引用已声明参数");
            }
            requiredText(entry.getValue(), path + "." + key, "覆盖值必须是字符串");
            references(projectId, entry.getValue(), path + "." + key, secrets);
        }
    }

    private static void sensitiveEntries(JsonNode node, String path, boolean everyEntry) {
        if (node == null || node.isNull()) return;
        int index = 0;
        for (JsonNode item : node) {
            String normalized = item.path("name").asText("").toLowerCase(java.util.Locale.ROOT)
                    .replace("_", "").replace("-", "");
            boolean sensitive = everyEntry || Set.of("authorization", "apikey", "xapikey", "cookie",
                    "setcookie", "token", "accesstoken", "refreshtoken").contains(normalized);
            if (sensitive && !secretTemplate(item.get("value"))) {
                throw invalid(path + "[" + index + "].value", "INVALID_SECRET_REFERENCE",
                        "敏感 Header/Cookie 值必须使用 ${secret:name} 引用");
            }
            index++;
        }
    }

    private static boolean secretTemplate(JsonNode value) {
        if (value == null || !value.isTextual()) return false;
        String text = value.textValue();
        return text.matches("\\$\\{secret:[A-Za-z0-9][A-Za-z0-9._-]*}")
                || text.matches("(?:Bearer|Basic) \\$\\{secret:[A-Za-z0-9][A-Za-z0-9._-]*}");
    }

    private static String body(UUID projectId, JsonNode node, String path, String method, SecretRepository secrets) {
        if (node == null || !node.isObject()) {
            throw invalid(path, "INVALID_BODY", "body 必须是 JSON 对象");
        }
        allowed(node, Set.of("type", "value"), path);
        String type = requiredText(node.get("type"), path + ".type", "body.type 必须是字符串");
        if (!Set.of("NONE", "JSON", "TEXT", "URLENCODED", "MULTIPART").contains(type)) {
            throw invalid(path + ".type", "INVALID_BODY", "body.type 不支持");
        }
        if (Set.of("GET", "HEAD", "OPTIONS").contains(method) && !"NONE".equals(type)) {
            throw invalid(path + ".type", "INVALID_BODY", method + " 请求的 body 必须为 NONE");
        }
        if ("NONE".equals(type) && node.has("value")) {
            throw invalid(path + ".value", "INVALID_BODY", "NONE body 不得包含 value");
        }
        if (Set.of("JSON", "TEXT", "URLENCODED", "MULTIPART").contains(type)) {
            if (!node.has("value")) {
                throw invalid(path + ".value", "INVALID_BODY", "Body 必须包含 value");
            }
            if ("TEXT".equals(type) && !node.get("value").isTextual()) {
                throw invalid(path + ".value", "INVALID_BODY", "TEXT body.value 必须是字符串");
            }
            if ("URLENCODED".equals(type)) {
                urlEncodedEntries(projectId, node.get("value"), path + ".value", secrets);
            }
            if ("MULTIPART".equals(type)) {
                multipart(projectId, node.get("value"), path + ".value", secrets);
            }
            references(projectId, node.get("value"), path + ".value", secrets);
        }
        return type;
    }

    private static void urlEncodedEntries(UUID projectId, JsonNode node, String path, SecretRepository secrets) {
        if (node == null || !node.isArray()) {
            throw invalid(path, "INVALID_JSON", "参数列表必须是 JSON 数组");
        }
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.get(index);
            String itemPath = path + "[" + index + "]";
            if (item == null || !item.isObject()) {
                throw invalid(itemPath, "INVALID_JSON", "参数项必须是 JSON 对象");
            }
            allowed(item, Set.of("name", "value", "enabled"), itemPath);
            String name = requiredText(item.get("name"), itemPath + ".name", "参数名不能为空");
            if (hasWhitespaceOrControl(name)) {
                throw invalid(itemPath + ".name", "INVALID_STRING", "参数名不能包含空白或控制字符");
            }
            JsonNode value = item.get("value");
            if (value == null || !value.isTextual()) {
                throw invalid(itemPath + ".value", "INVALID_STRING", "参数值必须是字符串");
            }
            if (item.get("enabled") == null) {
                throw invalid(itemPath + ".enabled", "INVALID_BOOLEAN", "enabled 必须是 boolean");
            }
            enabled(item.get("enabled"), itemPath + ".enabled");
            references(projectId, value, itemPath + ".value", secrets);
        }
    }

    private static void multipart(UUID projectId, JsonNode node, String path, SecretRepository secrets) {
        if (node != null && node.isArray()) {
            Set<String> fileIds = new HashSet<>();
            Set<String> wireNames = new HashSet<>();
            for (int index = 0; index < node.size(); index++) {
                JsonNode item = node.get(index);
                String itemPath = path + "[" + index + "]";
                if (!item.isObject()) throw invalid(itemPath, "INVALID_BODY", "Multipart 项必须是对象");
                allowed(item, Set.of("name", "kind", "value", "fileId", "contentType", "enabled"), itemPath);
                requiredText(item.get("name"), itemPath + ".name", "字段名不能为空");
                String kind = requiredText(item.get("kind"), itemPath + ".kind", "kind 必须是 TEXT 或 FILE");
                if ("TEXT".equals(kind)) {
                    requiredText(item.get("value"), itemPath + ".value", "文本字段值不能为空");
                } else if ("FILE".equals(kind)) {
                    String fileId = requiredText(item.get("fileId"), itemPath + ".fileId", "文件引用不能为空");
                    try { UUID.fromString(fileId); } catch (IllegalArgumentException exception) {
                        throw invalid(itemPath + ".fileId", "FILE_REFERENCE_INVALID", "文件引用必须是 fileId");
                    }
                    if (!fileIds.add(fileId)) throw invalid(itemPath + ".fileId", "DUPLICATE_FILE_REFERENCE", "文件引用不能重复");
                    String wireName = item.path("value").asText(item.path("name").asText());
                    if (!wireNames.add(wireName)) throw invalid(itemPath + ".name", "DUPLICATE_WIRE_FILE_NAME", "线上文件名不能重复");
                } else {
                    throw invalid(itemPath + ".kind", "INVALID_BODY", "kind 必须是 TEXT 或 FILE");
                }
            }
            return;
        }
        if (node == null || !node.isObject()) {
            throw invalid(path, "INVALID_BODY", "MULTIPART body.value 必须是对象");
        }
        allowed(node, Set.of("fields", "files"), path);
        if (node.has("fields")) {
            entries(projectId, node.get("fields"), path + ".fields", false, true, secrets);
        }
        if (node.has("files")) {
            JsonNode files = node.get("files");
            if (!files.isArray()) throw invalid(path + ".files", "INVALID_BODY", "files 必须是数组");
            int index = 0;
            for (JsonNode file : files) {
                String filePath = path + ".files[" + index + "]";
                if (!file.isObject()) throw invalid(filePath, "INVALID_BODY", "文件项必须是对象");
                allowed(file, Set.of("name", "fileId", "mimeType", "enabled"), filePath);
                String fieldName = requiredText(file.get("name"), filePath + ".name", "文件字段名不能为空");
                if (fieldName.contains("..") || fieldName.contains("/") || fieldName.contains("\\")) {
                    throw invalid(filePath + ".name", "INVALID_BODY", "文件字段名不能包含路径片段");
                }
                String fileId = requiredText(file.get("fileId"), filePath + ".fileId", "文件引用不能为空");
                try {
                    UUID.fromString(fileId);
                } catch (IllegalArgumentException exception) {
                    throw invalid(filePath + ".fileId", "FILE_REFERENCE_INVALID", "文件引用必须是 fileId");
                }
                if (file.has("mimeType")) requiredText(file.get("mimeType"), filePath + ".mimeType", "MIME 类型不能为空");
                enabled(file.get("enabled"), filePath + ".enabled");
                index++;
            }
        }
    }

    public static void validateOptions(UUID projectId, JsonNode node, String path, SecretRepository secrets) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (!node.isObject()) throw invalid(path, "INVALID_JSON", "options 必须是对象");
        allowed(node, Set.of("defaultHeaders", "followRedirects", "connectTimeoutMillis", "responseTimeoutMillis",
                "readTimeoutMillis", "totalTimeoutMillis", "proxy",
                "clientCertificate"), path);
        if (node.has("defaultHeaders")) {
            entries(projectId, node.get("defaultHeaders"), path + ".defaultHeaders", true, true, secrets);
            sensitiveEntries(node.get("defaultHeaders"), path + ".defaultHeaders", false);
        }
        if (node.has("followRedirects") && !node.get("followRedirects").isBoolean())
            throw invalid(path + ".followRedirects", "INVALID_BOOLEAN", "followRedirects 必须是 boolean");
        for (String field : List.of("connectTimeoutMillis", "responseTimeoutMillis", "readTimeoutMillis",
                "totalTimeoutMillis")) {
            if (node.has(field) && (!node.get(field).canConvertToInt()
                    || node.get(field).asInt() < 1 || node.get(field).asInt() > 120000))
                throw invalid(path + "." + field, "TIMEOUT_INVALID", field + " 必须在 1-120000 范围内");
        }
        if (node.has("proxy") && !node.get("proxy").isNull()) {
            JsonNode proxy = node.get("proxy");
            if (!proxy.isObject()) throw invalid(path + ".proxy", "INVALID_JSON", "proxy 必须是对象");
            allowed(proxy, Set.of("scheme", "host", "port", "username", "password", "passwordSecretRef"), path + ".proxy");
            requiredText(proxy.get("host"), path + ".proxy.host", "代理 host 不能为空");
            if (!proxy.has("port") || !proxy.get("port").canConvertToInt()
                    || proxy.get("port").asInt() < 1 || proxy.get("port").asInt() > 65535)
                throw invalid(path + ".proxy.port", "INVALID_NUMBER", "代理 port 必须在 1-65535 范围内");
            if (proxy.has("password") && !secretTemplate(proxy.get("password"))) {
                throw invalid(path + ".proxy.password", "INVALID_SECRET_REFERENCE",
                        "代理密码必须使用 ${secret:name} 引用");
            }
            if (proxy.has("passwordSecretRef") && !secretTemplate(proxy.get("passwordSecretRef"))) {
                throw invalid(path + ".proxy.passwordSecretRef", "INVALID_SECRET_REFERENCE",
                        "代理密码必须使用 ${secret:name} 引用");
            }
            if (proxy.has("password") && proxy.has("passwordSecretRef")) {
                throw invalid(path + ".proxy", "ONE_OF_VIOLATION", "代理密码只能使用一个密钥引用字段");
            }
            references(projectId, proxy, path + ".proxy", secrets);
        }
        if (node.has("clientCertificate")) {
            clientCertificate(projectId, node.get("clientCertificate"), path + ".clientCertificate", secrets);
        }
    }

    private static void clientCertificate(UUID projectId, JsonNode node, String path, SecretRepository secrets) {
        if (node == null || node.isNull()) return;
        if (!node.isObject()) throw invalid(path, "INVALID_JSON", "clientCertificate 必须是对象");
        allowed(node, Set.of("type", "secretRef", "passwordRef", "fileId", "passwordSecretRef"), path);
        if (!"PKCS12".equals(requiredText(node.get("type"), path + ".type", "证书类型不能为空"))) {
            throw invalid(path + ".type", "INVALID_CERTIFICATE", "客户端证书只支持 PKCS12");
        }
        boolean fileForm = node.has("fileId") || node.has("passwordSecretRef");
        if (fileForm && (node.has("secretRef") || node.has("passwordRef"))) {
            throw invalid(path, "ONE_OF_VIOLATION", "证书只能使用 fileId/passwordSecretRef 或旧版密钥引用");
        }
        if (!fileForm && (node.has("fileId") || node.has("passwordSecretRef"))) {
            throw invalid(path, "ONE_OF_VIOLATION", "证书引用字段组合不合法");
        }
        String passwordRef = fileForm ? requiredText(node.get("passwordSecretRef"), path + ".passwordSecretRef",
                "证书密码密钥引用不能为空") : requiredText(node.get("passwordRef"), path + ".passwordRef", "证书密码引用不能为空");
        if (fileForm) {
            String fileId = requiredText(node.get("fileId"), path + ".fileId", "证书文件引用不能为空");
            try {
                UUID.fromString(fileId);
            } catch (IllegalArgumentException exception) {
                throw invalid(path + ".fileId", "FILE_REFERENCE_INVALID", "证书文件引用必须是 fileId");
            }
        } else {
            String certificateRef = requiredText(node.get("secretRef"), path + ".secretRef", "证书密钥引用不能为空");
            if (!SECRET_NAME.matcher(secretName(certificateRef)).matches()) {
                throw invalid(path, "INVALID_SECRET_REFERENCE", "证书必须使用合法密钥引用");
            }
        }
        if (!SECRET_NAME.matcher(secretName(passwordRef)).matches()) {
            throw invalid(path, "INVALID_SECRET_REFERENCE", "证书密码必须使用合法密钥引用");
        }
        references(projectId, node, path, secrets);
    }

    private static String secretName(String reference) {
        if (reference == null || !reference.matches("\\$\\{secret:[A-Za-z0-9][A-Za-z0-9._-]*}")) {
            throw invalid("clientCertificate", "INVALID_SECRET_REFERENCE", "证书必须使用密钥引用");
        }
        return reference.substring("${secret:".length(), reference.length() - 1);
    }

    private static void assertion(UUID projectId, JsonNode node, String path, SecretRepository secrets,
                                  boolean allowLegacyHeaderCookieAssertions) {
        if (node == null || !node.isObject()) {
            throw invalid(path, "INVALID_ASSERTION", "断言必须是 JSON 对象");
        }
        String type = requiredText(node.get("type"), path + ".type", "断言类型不能为空");
        String operator = requiredText(node.get("operator"), path + ".operator", "断言操作符不能为空");
        if ("STATUS".equals(type)) {
            allowed(node, Set.of("type", "operator", "expected"), path);
            if (!"EQUALS".equals(operator) || !node.has("expected") || !node.get("expected").isIntegralNumber()
                    || node.get("expected").asInt() < 100 || node.get("expected").asInt() > 599) {
                throw invalid(path, "INVALID_ASSERTION", "STATUS 断言必须使用 100 到 599 的 EQUALS expected");
            }
            return;
        }
        if ("RESPONSE_TIME".equals(type)) {
            allowed(node, Set.of("type", "operator", "expected"), path);
            if (!"LESS_THAN".equals(operator) || !node.has("expected")
                    || !node.get("expected").isNumber() || node.get("expected").asDouble() < 0) {
                throw invalid(path, "INVALID_ASSERTION", "RESPONSE_TIME 断言必须使用非负毫秒数 LESS_THAN expected");
            }
            return;
        }
        if ("SCHEMA".equals(type)) {
            allowed(node, Set.of("type", "operator", "expected"), path);
            if (!"VALIDATE".equals(operator) || !node.has("expected") || !node.get("expected").isObject()) {
                throw invalid(path, "INVALID_ASSERTION", "SCHEMA 断言必须使用 VALIDATE 且 expected 必须是对象");
            }
            if (hasRemoteSchemaReference(node.get("expected"))) {
                throw invalid(path + ".expected", "INVALID_ASSERTION", "JSON Schema 不允许远程 $ref");
            }
            references(projectId, node.get("expected"), path + ".expected", secrets);
            return;
        }
        if ("BODY".equals(type)) {
            allowed(node, Set.of("type", "operator", "expected"), path);
            if (!Set.of("EQUALS", "CONTAINS", "NOT_CONTAINS", "MATCHES").contains(operator)
                    || !node.has("expected")) {
                throw invalid(path, "INVALID_ASSERTION", "BODY 断言必须包含合法操作符和 expected");
            }
            expectedType(node.get("expected"), operator, path + ".expected");
            if ("MATCHES".equals(operator)) safeRegex(node.get("expected"), path + ".expected");
            references(projectId, node.get("expected"), path + ".expected", secrets);
            return;
        }
        if (Set.of("HEADER", "COOKIE").contains(type)) {
            allowed(node, Set.of("type", "expression", "operator", "expected"), path);
            String expression = node.has("expression")
                    ? requiredText(node.get("expression"), path + ".expression", "Header/Cookie 目标名称不能为空") : "";
            if (expression.isBlank() && !allowLegacyHeaderCookieAssertions) {
                throw invalid(path + ".expression", "INVALID_ASSERTION", "新建 Header/Cookie 断言必须指定目标名称");
            }
            if (!expression.isBlank() && !VARIABLE_NAME.matcher(expression).matches()) {
                throw invalid(path + ".expression", "INVALID_ASSERTION", "Header/Cookie 目标名称不合法");
            }
            if (expression.isBlank() && Set.of("EXISTS", "NOT_EXISTS").contains(operator)) {
                throw invalid(path + ".expression", "INVALID_ASSERTION", "存在性断言必须指定 Header/Cookie 名称");
            }
            if (!Set.of("EXISTS", "NOT_EXISTS", "EQUALS", "CONTAINS", "NOT_CONTAINS", "MATCHES")
                    .contains(operator)) {
                throw invalid(path + ".operator", "INVALID_ASSERTION", type + " 操作符不支持");
            }
            boolean existence = Set.of("EXISTS", "NOT_EXISTS").contains(operator);
            if (existence && node.has("expected")) {
                throw invalid(path + ".expected", "INVALID_ASSERTION", operator + " 不得包含 expected");
            }
            if (!existence && !node.has("expected")) {
                throw invalid(path + ".expected", "INVALID_ASSERTION", operator + " 必须包含 expected");
            }
            if (!existence) expectedType(node.get("expected"), operator, path + ".expected");
            if (node.has("expected")) references(projectId, node.get("expected"), path + ".expected", secrets);
            if ("MATCHES".equals(operator) && node.has("expected")) {
                safeRegex(node.get("expected"), path + ".expected");
            }
            return;
        }
        if (Set.of("JSON_PATH", "JMES_PATH").contains(type)) {
            allowed(node, Set.of("type", "expression", "operator", "expected"), path);
            String expression = requiredText(node.get("expression"), path + ".expression", "结构化表达式不能为空");
            if (expression.length() > com.autotest.contracts.extraction.ControlledExtractionEvaluator.MAX_EXPRESSION_LENGTH
                    || hasControl(expression) || ("JSON_PATH".equals(type)
                    && (!expression.startsWith("$") || hasWhitespaceOrControl(expression)))) {
                throw invalid(path + ".expression", "INVALID_ASSERTION", "结构化表达式不合法");
            }
            if (!Set.of("EXISTS", "NOT_EXISTS", "EQUALS", "NOT_EQUALS", "GREATER_THAN", "LESS_THAN", "CONTAINS")
                    .contains(operator)) {
                throw invalid(path + ".operator", "INVALID_ASSERTION", type + " 操作符不支持");
            }
            boolean existence = Set.of("EXISTS", "NOT_EXISTS").contains(operator);
            if (existence && node.has("expected")) {
                throw invalid(path + ".expected", "INVALID_ASSERTION", operator + " 不得包含 expected");
            }
            if (!existence) {
                if (!node.has("expected")) {
                    throw invalid(path + ".expected", "INVALID_ASSERTION", operator + " 必须包含 expected");
                }
                expectedType(node.get("expected"), operator, path + ".expected");
                references(projectId, node.get("expected"), path + ".expected", secrets);
            }
            return;
        }
        if ("VARIABLE".equals(type)) {
            allowed(node, Set.of("type", "expression", "operator", "expected"), path);
            String expression = requiredText(node.get("expression"), path + ".expression", "变量名称不能为空");
            if (!VARIABLE_NAME.matcher(expression).matches() || expression.startsWith("secret:")) {
                throw invalid(path + ".expression", "INVALID_ASSERTION", "变量名称不合法");
            }
            if (!Set.of("EQUALS", "NOT_EQUALS", "GREATER_THAN", "LESS_THAN", "CONTAINS").contains(operator)
                    || !node.has("expected")) {
                throw invalid(path, "INVALID_ASSERTION", "VARIABLE 断言必须包含合法操作符和 expected");
            }
            expectedType(node.get("expected"), operator, path + ".expected");
            references(projectId, node.get("expected"), path + ".expected", secrets);
            return;
        }
        if ("XPATH".equals(type)) {
            allowed(node, Set.of("type", "expression", "operator"), path);
            String expression = requiredText(node.get("expression"), path + ".expression", "XPath 表达式不能为空");
            if (hasControl(expression) || !Set.of("EXISTS", "NOT_EXISTS").contains(operator)) {
                throw invalid(path, "INVALID_ASSERTION", "XPath 仅支持 EXISTS 或 NOT_EXISTS 且表达式不得含控制字符");
            }
            if (node.has("expected")) {
                throw invalid(path + ".expected", "INVALID_ASSERTION", "XPath 存在性断言不得包含 expected");
            }
            return;
        }
        throw invalid(path + ".type", "INVALID_ASSERTION", "断言类型不支持");
    }

    private static void extractors(UUID projectId, JsonNode node, String path, SecretRepository secrets) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (!node.isArray()) throw invalid(path, "INVALID_JSON", "提取器必须是 JSON 数组");
        int index = 0;
        for (JsonNode extractor : node) {
            String itemPath = path + "[" + index + "]";
            if (extractor == null || !extractor.isObject()) {
                throw invalid(itemPath, "INVALID_EXTRACTOR", "提取器必须是 JSON 对象");
            }
            allowed(extractor, Set.of("type", "expression", "variable", "defaultValue", "failIfMissing"), itemPath);
            String type = requiredText(extractor.get("type"), itemPath + ".type", "提取器类型不能为空");
            if (!Set.of("JSON_PATH", "JMESPATH", "XPATH", "REGEX", "HEADER", "COOKIE").contains(type)) {
                throw invalid(itemPath + ".type", "INVALID_EXTRACTOR", "提取器类型不支持");
            }
            String expression = requiredText(extractor.get("expression"), itemPath + ".expression",
                    "提取表达式不能为空");
            if (expression.length() > com.autotest.contracts.extraction.ControlledExtractionEvaluator.MAX_EXPRESSION_LENGTH
                    || hasControl(expression)
                    || ("JSON_PATH".equals(type)
                    && (!expression.startsWith("$") || hasWhitespaceOrControl(expression)))) {
                throw invalid(itemPath + ".expression", "INVALID_EXTRACTOR_EXPRESSION", "提取表达式不合法");
            }
            if (Set.of("HEADER", "COOKIE").contains(type) && !VARIABLE_NAME.matcher(expression).matches()) {
                throw invalid(itemPath + ".expression", "INVALID_HEADER_NAME", "Header/Cookie 名称不合法");
            }
            if ("REGEX".equals(type)) safeRegex(textNode(expression), itemPath + ".expression");
            String variable = requiredText(extractor.get("variable"), itemPath + ".variable", "提取变量名不能为空");
            if (!VARIABLE_NAME.matcher(variable).matches() || variable.startsWith("secret:")) {
                throw invalid(itemPath + ".variable", "INVALID_VARIABLE_NAME", "提取变量名不合法");
            }
            if (extractor.has("failIfMissing") && !extractor.get("failIfMissing").isBoolean()) {
                throw invalid(itemPath + ".failIfMissing", "INVALID_BOOLEAN", "failIfMissing 必须是 boolean");
            }
            if (extractor.has("defaultValue")) {
                references(projectId, extractor.get("defaultValue"), itemPath + ".defaultValue", secrets);
            }
            index++;
        }
    }

    private static void references(UUID projectId, JsonNode node, String path, SecretRepository secrets) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            tokens(projectId, node.textValue(), path, secrets);
        } else if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                references(projectId, field.getValue(), path + "." + field.getKey(), secrets);
            }
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode value : node) {
                references(projectId, value, path + "[" + index + "]", secrets);
                index++;
            }
        }
    }

    private static void tokens(UUID projectId, String text, String path, SecretRepository secrets) {
        int start = text.indexOf("${");
        while (start >= 0) {
            int close = text.indexOf('}', start + 2);
            if (close < 0) {
                throw invalid(path, "INVALID_TOKEN", "变量引用未闭合");
            }
            String token = text.substring(start + 2, close);
            if (token.contains("${")) {
                throw invalid(path, "INVALID_TOKEN", "变量引用不合法");
            }
            if (token.startsWith("secret:")) {
                String secretName = token.substring("secret:".length());
                if (!SECRET_NAME.matcher(secretName).matches()) {
                    throw invalid(path, "INVALID_TOKEN", "密钥引用不合法");
                }
                if (secrets.findActiveByName(projectId, secretName) == null) {
                    throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "SECRET_REFERENCE_NOT_FOUND",
                            "密钥引用不存在");
                }
            } else if (!VARIABLE_NAME.matcher(token).matches()) {
                throw invalid(path, "INVALID_TOKEN", "变量引用不合法");
            }
            start = text.indexOf("${", close + 1);
        }
    }

    private static void allowed(JsonNode node, Set<String> allowed, String path) {
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                throw invalid(path + "." + field, "UNKNOWN_FIELD", "字段不支持");
            }
        }
    }

    private static void required(JsonNode node, String field, String path) {
        if (!node.has(field)) {
            throw invalid(path + "." + field, "MISSING_FIELD", "字段不能为空");
        }
    }

    private static String requiredText(JsonNode node, String path, String message) {
        if (node == null || !node.isTextual() || node.textValue().isEmpty()) {
            throw invalid(path, "INVALID_STRING", message);
        }
        return node.textValue();
    }

    private static void enabled(JsonNode node, String path) {
        if (node != null && !node.isBoolean()) {
            throw invalid(path, "INVALID_BOOLEAN", "enabled 必须是 boolean");
        }
    }

    private static boolean hasWhitespaceOrControl(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isWhitespace(codePoint)
                || Character.isISOControl(codePoint));
    }

    private static boolean hasControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static boolean hasRemoteSchemaReference(JsonNode node) {
        if (node == null) return false;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if ("$ref".equals(field.getKey())
                        && (!field.getValue().isTextual()
                        || isRemoteSchemaReference(field.getValue().textValue()))) {
                    return true;
                }
                if (hasRemoteSchemaReference(field.getValue())) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) if (hasRemoteSchemaReference(value)) return true;
        }
        return false;
    }

    private static boolean isRemoteSchemaReference(String reference) {
        String value = reference == null ? "" : reference.strip();
        return value.startsWith("//") || value.matches("[A-Za-z][A-Za-z0-9+.-]*:.*");
    }

    private static void safeRegex(JsonNode node, String path) {
        if (node == null || !node.isTextual()) {
            throw invalid(path, "INVALID_ASSERTION", "正则断言 expected 必须是字符串");
        }
        try {
            SafeRegex.compile(node.textValue());
        } catch (IllegalArgumentException exception) {
            throw invalid(path, "INVALID_REGEX", "正则表达式超过受控限制");
        }
    }

    private static void expectedType(JsonNode expected, String operator, String path) {
        if (Set.of("GREATER_THAN", "LESS_THAN").contains(operator)
                && (expected == null || !expected.isNumber())) {
            throw invalid(path, "INVALID_ASSERTION", operator + " expected 必须是数字");
        }
        if ("MATCHES".equals(operator) && (expected == null || !expected.isTextual())) {
            throw invalid(path, "INVALID_ASSERTION", "MATCHES expected 必须是字符串");
        }
    }

    private static ApiDomainException invalid(String path, String code, String message) {
        Map<String, Object> fieldError = Map.of("path", path, "code", code, "message", message);
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", "请求参数不合法",
                Map.of("fieldErrors", List.of(fieldError)));
    }
}
