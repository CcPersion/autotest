package com.autotest.runner;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Base64;
import java.net.URLDecoder;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** 解析 JMeter CSV JTL；只保留平台报告需要的脱敏字段。 */
public final class JtlParser {

    private static final int MAX_TEXT_LENGTH = 4096;
    private static final Pattern STEP_LABEL = Pattern.compile("^.*\\[([^\\]]+)]$");
    private static final Pattern QUERY_PARAMETER = Pattern.compile("([?&])([^&#=]*)(=)([^&#]*)");
    private static final java.util.Set<String> SENSITIVE_QUERY_KEYS = java.util.Set.of(
            "token", "password", "secret", "apikey", "xapikey", "accesstoken", "refreshtoken",
            "authorization", "cookie", "cookies", "setcookie");

    public List<JtlSample> parse(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("JTL 文件不存在");
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (content.stripLeading().startsWith("<")) {
            return parseXml(content);
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = nextNonEmpty(reader);
            if (headerLine == null) {
                return List.of();
            }
            List<String> headers = splitCsv(stripBom(headerLine));
            Map<String, Integer> columns = index(headers);
            require(columns, "label");
            List<JtlSample> samples = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> values = splitCsv(line);
                samples.add(sample(columns, values));
            }
            return List.copyOf(samples);
        }
    }

    private static List<JtlSample> parseXml(String content) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(
                    new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            List<JtlSample> samples = new ArrayList<>();
            collectXmlSamples(document.getDocumentElement(), samples);
            return List.copyOf(samples);
        } catch (Exception exception) {
            throw new IOException("JTL XML 解析失败", exception);
        }
    }

    private static void collectXmlSamples(Node node, List<JtlSample> samples) {
        if (node.getNodeType() == Node.ELEMENT_NODE
                && ("httpSample".equals(node.getNodeName()) || "sample".equals(node.getNodeName()))) {
            samples.add(xmlSample((Element) node));
            return;
        }
        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            collectXmlSamples(children.item(index), samples);
        }
    }

    private static JtlSample xmlSample(Element sample) {
        String label = sample.getAttribute("lb");
        Matcher matcher = STEP_LABEL.matcher(label);
        String stepId = matcher.matches() ? matcher.group(1) : label;
        String failure = "";
        NodeList assertions = sample.getElementsByTagName("assertionResult");
        StringBuilder messages = new StringBuilder();
        List<JtlAssertionResult> assertionResults = new ArrayList<>();
        for (int index = 0; index < assertions.getLength(); index++) {
            Element assertion = (Element) assertions.item(index);
            String message = truncate(text(assertion, "failureMessage"));
            boolean failed = flag(assertion, "failure") || flag(assertion, "error");
            assertionResults.add(new JtlAssertionResult(truncate(text(assertion, "name")), !failed, message));
            if (failed && !message.isBlank()) {
                if (messages.length() > 0) messages.append('\n');
                messages.append(message);
            }
        }
        if (messages.length() > 0) failure = messages.toString();
        return new JtlSample(stepId,
                longValue(sample.getAttribute("t")),
                intValue(sample.getAttribute("rc")),
                truncate(sample.getAttribute("rm")),
                Boolean.parseBoolean(sample.getAttribute("s")),
                truncate(failure),
                sanitizeUrl(text(sample, "java.net.URL")),
                extractionJson(text(sample, "responseHeader")),
                truncate(text(sample, "responseData")), "", "",
                truncate(text(sample, "responseHeader")), assertionResults);
    }

    private static String text(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        if (nodes.getLength() == 0) return "";
        return nodes.item(0).getTextContent();
    }

    private static JtlSample sample(Map<String, Integer> columns, List<String> values) {
        String label = value(columns, values, "label");
        Matcher matcher = STEP_LABEL.matcher(label);
        String stepId = matcher.matches() ? matcher.group(1) : label;
        return new JtlSample(stepId,
                longValue(value(columns, values, "elapsed")),
                intValue(value(columns, values, "responseCode")),
                truncate(value(columns, values, "responseMessage")),
                Boolean.parseBoolean(value(columns, values, "success")),
                truncate(value(columns, values, "failureMessage")),
                sanitizeUrl(truncate(value(columns, values, "url"))),
                extractionJson(value(columns, values, "responseHeaders")),
                truncate(firstValue(columns, values, "responseData", "responseBody")), "", "",
                truncate(value(columns, values, "responseHeaders")), List.of());
    }

    private static boolean flag(Element element, String name) {
        String attribute = element.getAttribute(name);
        if (!attribute.isBlank()) return "true".equalsIgnoreCase(attribute);
        return "true".equalsIgnoreCase(text(element, name).trim());
    }

    private static Map<String, Integer> index(List<String> headers) {
        Map<String, Integer> result = new HashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            result.put(headers.get(index).trim().toLowerCase(Locale.ROOT), index);
        }
        return result;
    }

    private static void require(Map<String, Integer> columns, String name) {
        if (!columns.containsKey(name)) {
            throw new IllegalArgumentException("JTL 缺少列: " + name);
        }
    }

    private static String value(Map<String, Integer> columns, List<String> values, String name) {
        Integer index = columns.get(name.toLowerCase(Locale.ROOT));
        if (index == null || index >= values.size()) {
            return "";
        }
        return values.get(index);
    }

    private static long longValue(String value) {
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int intValue(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
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
            if (SENSITIVE_QUERY_KEYS.contains(normalizeKey(decoded))) {
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

    private static String firstValue(Map<String, Integer> columns, List<String> values, String... names) {
        for (String name : names) {
            String value = value(columns, values, name);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String extractionJson(String responseHeaders) {
        if (responseHeaders == null || responseHeaders.isBlank()) {
            return "[]";
        }
        for (String line : responseHeaders.split("\\R")) {
            if (!line.startsWith("X-Autotest-Extractions:")) {
                continue;
            }
            try {
                String encoded = line.substring("X-Autotest-Extractions:".length()).trim();
                String json = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                return json.startsWith("[") ? json : "[]";
            } catch (IllegalArgumentException ignored) {
                return "[]";
            }
        }
        return "[]";
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_TEXT_LENGTH) {
            return value == null ? "" : value;
        }
        return value.substring(0, MAX_TEXT_LENGTH) + "…[已截断]";
    }

    private static String nextNonEmpty(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return null;
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static List<String> splitCsv(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        values.add(current.toString());
        return values;
    }
}
