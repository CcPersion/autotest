package com.autotest.platform.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只解析常用 Curl 语法，不执行任何 Shell。 */
public final class CurlImportParser {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> SENSITIVE = Set.of("authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "api-key", "token", "access-token", "refresh-token");
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^{}]+)}");
    private final ObjectMapper mapper;

    public CurlImportParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ImportDocument parse(String source) {
        List<String> tokens = tokenize(source);
        if (tokens.isEmpty() || !"curl".equalsIgnoreCase(tokens.get(0))) {
            throw error("curl.command", "内容必须以 curl 开头");
        }
        String url = null;
        String method = null;
        boolean getWithData = false;
        List<String> headers = new ArrayList<>();
        List<String> cookies = new ArrayList<>();
        List<String> data = new ArrayList<>();
        List<String> encodedData = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals("-G") || token.equals("--get")) {
                getWithData = true;
                continue;
            }
            if (token.equals("-X") || token.equals("--request")) {
                method = argument(tokens, ++i, "curl.request").toUpperCase(Locale.ROOT);
                if (!METHODS.contains(method)) throw error("curl.request", "HTTP 方法不支持");
                continue;
            }
            if (token.equals("-H") || token.equals("--header")) {
                headers.add(argument(tokens, ++i, "curl.header"));
                continue;
            }
            if (token.equals("-b") || token.equals("--cookie")) {
                cookies.add(argument(tokens, ++i, "curl.cookie"));
                continue;
            }
            if (token.equals("-d") || token.equals("--data") || token.equals("--data-raw")
                    || token.equals("--data-binary")) {
                String value = argument(tokens, ++i, "curl.data");
                if (value.startsWith("@")) throw error("curl.data", "禁止导入文件路径或文件上传");
                data.add(value);
                continue;
            }
            if (token.equals("--data-urlencode")) {
                String value = argument(tokens, ++i, "curl.data-urlencode");
                if (value.startsWith("@")) throw error("curl.data-urlencode", "禁止导入文件路径或文件上传");
                encodedData.add(value);
                continue;
            }
            if (token.equals("--url")) {
                url = argument(tokens, ++i, "curl.url");
                continue;
            }
            if (token.equals("--compressed") || token.equals("-k") || token.equals("--insecure")
                    || token.equals("-s") || token.equals("--silent") || token.equals("-L")
                    || token.equals("--location")) {
                continue;
            }
            if (token.startsWith("-")) {
                throw error("curl.option[" + i + "]", "Curl 选项不在受控白名单中");
            }
            if (url != null) throw error("curl.url", "只能指定一个 URL");
            url = token;
        }
        if (url == null || url.isBlank()) throw error("curl.url", "URL 不能为空");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw error("curl.url", "URL 不合法");
        }
        if (!Set.of("http", "https").contains(uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT))
                || uri.getHost() == null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw error("curl.url", "只允许 HTTP/HTTPS URL");
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        String methodValue = method == null ? (data.isEmpty() && encodedData.isEmpty() ? "GET" : "POST") : method;
        if (getWithData && method == null) methodValue = "GET";
        ArrayNode query = mapper.createArrayNode();
        Map<String, String> queryValues = parseQuery(uri.getRawQuery());
        if (getWithData) {
            queryValues.putAll(parseForm(data, "curl.data"));
            queryValues.putAll(parseForm(encodedData, "curl.data-urlencode"));
            data.clear();
            encodedData.clear();
        }
        queryValues.forEach((name, value) -> query.add(entry(name, value, true)));
        ArrayNode headerNodes = mapper.createArrayNode();
        String contentType = null;
        for (int i = 0; i < headers.size(); i++) {
            String raw = headers.get(i);
            int colon = raw.indexOf(':');
            if (colon <= 0) throw error("curl.header[" + i + "]", "Header 必须包含名称和值");
            String name = raw.substring(0, colon).trim();
            String value = raw.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("content-type")) contentType = value.toLowerCase(Locale.ROOT);
            if (isSensitive(name)) {
                String ref = "${secret:imported_" + safeName(name) + "_" + (i + 1) + "}";
                headerNodes.add(entry(name, ref, true));
                warnings.add("Header " + name + " 的值已脱敏，确认前必须绑定已有密钥");
            } else {
                headerNodes.add(entry(name, value, true));
            }
        }
        ArrayNode cookieNodes = mapper.createArrayNode();
        int cookieIndex = 0;
        for (String cookie : cookies) {
            for (String piece : cookie.split(";")) {
                int equals = piece.indexOf('=');
                if (equals <= 0) throw error("curl.cookie[" + cookieIndex + "]", "Cookie 必须包含名称和值");
                String name = piece.substring(0, equals).trim();
                cookieNodes.add(entry(name, "${secret:imported_cookie_" + safeName(name) + "_" + (++cookieIndex) + "}", true));
                warnings.add("Cookie " + name + " 的值已脱敏，确认前必须绑定已有密钥");
            }
        }
        ObjectNode body = body(methodValue, contentType, data, encodedData);
        ArrayNode pathParams = mapper.createArrayNode();
        Matcher matcher = PATH_PARAMETER.matcher(path);
        while (matcher.find()) pathParams.add(entry(matcher.group(1), "${" + matcher.group(1) + "}", false));
        String base = uri.getScheme() + "://" + uri.getRawAuthority() + path;
        String name = methodValue + " " + path;
        ObjectNode requestSpec = mapper.createObjectNode().set("pathParams", pathParams);
        requestSpec.set("query", query);
        requestSpec.set("headers", headerNodes);
        requestSpec.set("cookies", cookieNodes);
        requestSpec.set("body", body);
        requestSpec.set("options", mapper.createObjectNode());
        ObjectNode caseSpec = mapper.createObjectNode();
        caseSpec.set("pathParams", objectOverrides(pathParams));
        caseSpec.set("query", objectOverrides(query));
        caseSpec.set("headers", mapper.createObjectNode());
        caseSpec.set("cookies", mapper.createObjectNode());
        caseSpec.set("body", body.deepCopy());
        caseSpec.set("extractors", mapper.createArrayNode());
        ImportCandidate candidate = new ImportCandidate("curl", name, name, methodValue, base, requestSpec,
                caseSpec, mapper.createObjectNode(), mapper.createArrayNode(), warnings);
        return new ImportDocument("CURL", List.of(candidate), warnings);
    }

    private ObjectNode body(String method, String contentType, List<String> data, List<String> encoded) {
        ObjectNode node = mapper.createObjectNode();
        if (data.isEmpty() && encoded.isEmpty()) {
            node.put("type", "NONE");
            return node;
        }
        String value = String.join("&", !encoded.isEmpty() ? encoded : data);
        if (contentType != null && contentType.contains("application/x-www-form-urlencoded") || !encoded.isEmpty()) {
            node.put("type", "URLENCODED");
            ArrayNode entries = mapper.createArrayNode();
            parseForm(List.of(value), "curl.data").forEach((name, item) -> entries.add(entry(name, item, true)));
            node.set("value", entries);
        } else if (contentType != null && contentType.contains("json") || value.trim().startsWith("{") || value.trim().startsWith("[")) {
            try {
                node.put("type", "JSON");
                node.set("value", mapper.readTree(value));
            } catch (Exception exception) {
                throw error("curl.data", "JSON body 格式不正确");
            }
        } else {
            node.put("type", "TEXT");
            node.put("value", value);
        }
        if (Set.of("GET", "HEAD", "OPTIONS").contains(method) && !"NONE".equals(node.get("type").asText())) {
            throw error("curl.data", method + " 请求不允许携带 body");
        }
        return node;
    }

    private Map<String, String> parseQuery(String query) {
        if (query == null || query.isBlank()) return new LinkedHashMap<>();
        return parseForm(List.of(query), "curl.url.query");
    }

    private Map<String, String> parseForm(List<String> values, String path) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String value : values) {
            for (String part : value.split("&")) {
                if (part.isBlank()) continue;
                int equals = part.indexOf('=');
                String rawName = equals < 0 ? part : part.substring(0, equals);
                String rawValue = equals < 0 ? "" : part.substring(equals + 1);
                if (rawName.isBlank()) throw error(path, "参数名不能为空");
                result.put(decode(rawName), decode(rawValue));
            }
        }
        return result;
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw error("curl.query", "URL 编码不合法");
        }
    }

    private ObjectNode objectOverrides(ArrayNode entries) {
        ObjectNode node = mapper.createObjectNode();
        entries.forEach(item -> node.set(item.get("name").asText(), item.get("value")));
        return node;
    }

    private ObjectNode entry(String name, String value, boolean enabled) {
        ObjectNode node = mapper.createObjectNode();
        node.put("name", name);
        node.put("value", value);
        if (enabled) node.put("enabled", true);
        return node;
    }

    private List<String> tokenize(String source) {
        if (source == null || source.isBlank()) throw error("curl", "Curl 内容不能为空");
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '$' && i + 1 < source.length() && source.charAt(i + 1) == '(') {
                throw error("curl", "Curl 不允许包含 Shell 替换");
            }
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && quote != '\'') {
                escaped = true;
                continue;
            }
            if (quote != 0) {
                if (c == quote) quote = 0;
                else if (Character.isISOControl(c) && c != '\t') throw error("curl", "内容含控制字符");
                else current.append(c);
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else if (c == '`' || c == ';' || c == '|' || c == '&' || c == '<' || c == '>') {
                throw error("curl", "Curl 不允许包含 Shell 操作符");
            } else {
                current.append(c);
            }
        }
        if (escaped || quote != 0) throw error("curl", "引号或转义未闭合");
        if (!current.isEmpty()) tokens.add(current.toString());
        return tokens;
    }

    private String argument(List<String> tokens, int index, String path) {
        if (index >= tokens.size()) throw error(path, "选项缺少参数");
        return tokens.get(index);
    }

    private boolean isSensitive(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replace('_', '-');
        return SENSITIVE.contains(normalized) || normalized.endsWith("-token") || normalized.endsWith("-key");
    }

    private String safeName(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private ImportParseException error(String path, String message) {
        return new ImportParseException(path, message);
    }
}
