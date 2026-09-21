package com.autotest.contracts.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 平台试算与 Runner 报告共用的受控表达式语义。
 * 只实现受控声明式 JSON 路径、XPath、正则、Header 和 Cookie，不执行脚本或外部资源。
 */
public final class ControlledExtractionEvaluator {

    public static final int MAX_BODY_LENGTH = 1024 * 1024;
    public static final int MAX_EXPRESSION_LENGTH = 4096;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TOKEN = Pattern.compile("(?:\\.([A-Za-z_][A-Za-z0-9_-]*))|(?:\\[\\s*([0-9]+|\\*|['\"]([^'\"]+)['\"])\\s*])");
    private static final Pattern JMES_TOKEN = Pattern.compile(
            "(?:\\.([A-Za-z_][A-Za-z0-9_-]*))"
                    + "|(?:\\[\\s*([0-9]+|\\*)\\s*])"
                    + "|(?:\\[\\?\\s*([A-Za-z_][A-Za-z0-9_-]*)\\s*(?:(==|!=)\\s*(`[^`]*`|'[^']*'))?\\s*])"
                    + "|(?:\\[\\s*['\"]([^'\"]+)['\"]\\s*])");

    private ControlledExtractionEvaluator() {
    }

    public static ControlledExtractionResult extract(int ruleIndex, ExtractionRule rule,
                                                     HttpResponseSample response) {
        try {
            ensureLimits(rule.expression(), response.bodyText());
            JsonNode value = evaluate(rule.type(), rule.expression(), response);
            boolean matched = value != null;
            boolean usedDefault = false;
            JsonNode resultValue = value;
            if (!matched && rule.defaultValue() != null) {
                resultValue = rule.defaultValue();
                usedDefault = true;
            }
            boolean failed = !matched && rule.failIfMissing();
            String message = matched ? "已命中" : usedDefault ? "未命中，使用默认值" : "未命中";
            if (failed) message += "；规则要求未命中时失败";
            return new ControlledExtractionResult(ruleIndex, rule.type(), rule.expression(), rule.variable(),
                    matched, usedDefault, resultValue, valueType(resultValue), failed, "", message);
        } catch (PatternSyntaxException exception) {
            return invalid(ruleIndex, rule, "正则表达式不合法");
        } catch (IllegalArgumentException exception) {
            return invalid(ruleIndex, rule, exception.getMessage());
        } catch (StackOverflowError error) {
            return invalid(ruleIndex, rule, "正则表达式求值超出受控限制");
        } catch (Exception exception) {
            return invalid(ruleIndex, rule, "表达式求值失败");
        }
    }

    public static JsonNode evaluate(String type, String expression, HttpResponseSample response) {
        if (type == null || expression == null || response == null) throw new IllegalArgumentException("试算参数不能为空");
        return switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case "JSON_PATH" -> jsonPath(response.bodyText(), expression);
            case "JMESPATH", "JMES_PATH" -> jmesPath(response.bodyText(), expression);
            case "XPATH" -> xpath(response.bodyText(), expression);
            case "REGEX" -> regex(response.bodyText(), expression);
            case "HEADER" -> findHeader(response.headers(), expression);
            case "COOKIE" -> findCookie(response.cookies(), expression);
            default -> throw new IllegalArgumentException("不支持的表达式类型");
        };
    }

    private static JsonNode jsonPath(String body, String expression) {
        JsonNode root;
        try {
            root = JSON.readTree(body == null ? "" : body);
        } catch (Exception exception) {
            throw new IllegalArgumentException("响应体不是合法 JSON");
        }
        String normalized = expression.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("表达式不能为空");
        if (!normalized.startsWith("$")) {
            throw new IllegalArgumentException("JSONPath 必须以 $ 开头");
        }
        if (normalized.startsWith("$")) {
            normalized = normalized.substring(1);
        }
        return first(jsonPathValues(root, normalized, false));
    }

    private static JsonNode jmesPath(String body, String expression) {
        JsonNode root;
        try {
            root = JSON.readTree(body == null ? "" : body);
        } catch (Exception exception) {
            throw new IllegalArgumentException("响应体不是合法 JSON");
        }
        String normalized = expression.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("表达式不能为空");
        List<String> stages = splitPipe(normalized);
        List<JsonNode> current = jsonPathValues(root, stages.get(0), true);
        if (stages.size() == 1) return first(current);

        String selector = stages.get(1).trim();
        Matcher index = Pattern.compile("\\[\\s*([0-9]+)\\s*]").matcher(selector);
        if (!index.matches()) {
            throw new IllegalArgumentException("JMESPath pipe 只支持受控数组下标");
        }
        int selected = Integer.parseInt(index.group(1));
        if (current.size() == 1 && current.get(0) != null && current.get(0).isArray()) {
            JsonNode array = current.get(0);
            return selected < array.size() ? array.get(selected) : null;
        }
        return selected < current.size() ? current.get(selected) : null;
    }

    private static List<String> splitPipe(String expression) {
        List<String> stages = new ArrayList<>();
        int start = 0;
        char quote = 0;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (quote != 0) {
                if (current == quote) quote = 0;
            } else if (current == '\'' || current == '\"' || current == '`') {
                quote = current;
            } else if (current == '|') {
                stages.add(expression.substring(start, index).trim());
                start = index + 1;
            }
        }
        stages.add(expression.substring(start).trim());
        if (quote != 0 || stages.size() > 2 || stages.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("JMESPath pipe 表达式不合法");
        }
        return stages;
    }

    private static List<JsonNode> jsonPathValues(JsonNode root, String expression, boolean jmes) {
        String normalized = expression.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("表达式不能为空");
        if (normalized.startsWith("$")) normalized = normalized.substring(1);
        if (normalized.startsWith(".")) normalized = normalized.substring(1);
        String tokenInput = "." + normalized;
        List<JsonNode> current = new ArrayList<>(List.of(root));
        int offset = 0;
        Matcher matcher = (jmes ? JMES_TOKEN : TOKEN).matcher(tokenInput);
        while (matcher.find()) {
            if (matcher.start() != offset) {
                throw new IllegalArgumentException("JSON 路径表达式不合法");
            }
            offset = matcher.end();
            String property = matcher.group(1);
            String bracket = matcher.group(2);
            String filter = jmes ? matcher.group(3) : null;
            String operator = jmes ? matcher.group(4) : null;
            String literal = jmes ? matcher.group(5) : null;
            String quoted = jmes ? matcher.group(6) : matcher.group(3);
            String key = property != null ? property : quoted;
            List<JsonNode> next = new ArrayList<>();
            JsonNode expected = literal == null ? null : jmesLiteral(literal);
            for (JsonNode node : current) {
                if (key != null) {
                    if (node != null && node.isObject() && node.has(key)) next.add(node.get(key));
                } else if (filter != null) {
                    if (node != null && node.isArray()) {
                        node.forEach(candidate -> {
                            JsonNode value = candidate != null && candidate.isObject()
                                    ? candidate.get(filter) : null;
                            if (operator == null ? truthy(value) : matchesLiteral(value, operator, expected)) {
                                next.add(candidate);
                            }
                        });
                    }
                } else if ("*".equals(bracket)) {
                    if (node != null && node.isArray()) node.forEach(next::add);
                    else if (node != null && node.isObject()) node.elements().forEachRemaining(next::add);
                } else if (node != null && node.isArray()) {
                    int index = Integer.parseInt(bracket);
                    if (index >= 0 && index < node.size()) next.add(node.get(index));
                }
            }
            current = next;
            if (current.isEmpty()) return List.of();
        }
        if (offset != tokenInput.length()) {
            throw new IllegalArgumentException("JSON 路径表达式不完整");
        }
        return current;
    }

    private static JsonNode jmesLiteral(String literal) {
        if (literal.startsWith("`") && literal.endsWith("`")) {
            String jsonLiteral = literal.substring(1, literal.length() - 1);
            try {
                JsonNode value = JSON.readTree(jsonLiteral);
                if (value == null) throw new IllegalArgumentException("JMESPath literal 不能为空");
                return value;
            } catch (Exception exception) {
                throw new IllegalArgumentException("JMESPath literal 不合法");
            }
        }
        if (literal.startsWith("'") && literal.endsWith("'")) {
            return JsonNodeFactory.instance.textNode(literal.substring(1, literal.length() - 1));
        }
        throw new IllegalArgumentException("JMESPath literal 不受支持");
    }

    private static boolean matchesLiteral(JsonNode actual, String operator, JsonNode expected) {
        boolean equal = actual != null && !actual.isMissingNode() && expected != null
                && (actual.isNumber() && expected.isNumber()
                ? actual.decimalValue().compareTo(expected.decimalValue()) == 0
                : actual.equals(expected));
        return "==".equals(operator) ? equal : !equal;
    }

    private static boolean truthy(JsonNode value) {
        if (value == null || value.isNull() || value.isBoolean() && !value.booleanValue()) {
            return false;
        }
        if (value.isNumber()) return value.asDouble() != 0;
        if (value.isTextual()) return !value.textValue().isEmpty();
        if (value.isArray()) return !value.isEmpty();
        return true;
    }

    private static JsonNode first(List<JsonNode> values) {
        // JMeter matchNumber=1: a projection chooses its first match. A selected
        // array (for example $.items) remains an array and is not flattened.
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static JsonNode xpath(String body, String expression) {
        try {
            Document document = secureXml(body);
            var xpath = XPathFactory.newInstance().newXPath();
            try {
                NodeList nodes = (NodeList) xpath.evaluate(expression, document, XPathConstants.NODESET);
                if (nodes.getLength() == 0) return null;
                Node node = nodes.item(0);
                return JsonNodeFactory.instance.textNode(node.getNodeValue() == null
                        ? node.getTextContent() : node.getNodeValue());
            } catch (XPathExpressionException nodeSetFailure) {
                String value = xpath.evaluate(expression, document);
                return value == null || value.isEmpty() ? null : JsonNodeFactory.instance.textNode(value);
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("XPath 表达式或 XML 响应不合法");
        }
    }

    private static Document secureXml(String body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonNode regex(String body, String expression) {
        String input = body == null ? "" : body;
        SafeRegex.ensureInputLength(input);
        try {
            Matcher matcher = SafeRegex.compile(expression).matcher(input);
            if (!matcher.find()) return null;
            return JsonNodeFactory.instance.textNode(matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group());
        } catch (StackOverflowError error) {
            throw new IllegalArgumentException("正则表达式求值超出受控限制");
        }
    }

    private static JsonNode findHeader(Map<String, String> headers, String expression) {
        String expected = expression.trim();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(expected)) return text(entry.getValue());
        }
        return null;
    }

    private static JsonNode findCookie(Map<String, String> cookies, String expression) {
        String value = cookies.get(expression.trim());
        return value == null ? null : text(value);
    }

    private static JsonNode text(String value) {
        return value == null ? JsonNodeFactory.instance.nullNode() : JsonNodeFactory.instance.textNode(value);
    }

    private static ControlledExtractionResult invalid(int ruleIndex, ExtractionRule rule, String message) {
        return new ControlledExtractionResult(ruleIndex, rule.type(), rule.expression(), rule.variable(),
                false, false, null, "missing", true, "INVALID_EXPRESSION", message == null ? "表达式不合法" : message);
    }

    private static void ensureLimits(String expression, String body) {
        if (expression == null || expression.length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException("表达式超过长度限制");
        }
        if (body != null && body.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("响应体超过长度限制");
        }
    }

    public static String valueType(JsonNode value) {
        if (value == null) return "missing";
        if (value.isNull()) return "null";
        if (value.isObject()) return "object";
        if (value.isArray()) return "array";
        if (value.isBoolean()) return "boolean";
        if (value.isNumber()) return "number";
        return "string";
    }
}
