package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.testelement.AbstractTestElement;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** 将白名单提取器的结果写入当前样本的脱敏元数据，不执行用户脚本。 */
public final class JmeterExtractionReporter extends AbstractTestElement implements PostProcessor {

    private static final String DEFINITIONS = "autotest.extraction.definitions";
    private static final String HEADER = "X-Autotest-Extractions";
    private static final String STATE_UNCONFIGURED = "UNCONFIGURED";
    private static final String STATE_NULL = "NULL";
    private static final String STATE_EMPTY = "EMPTY";
    private static final String STATE_VALUE = "VALUE";
    private static final String EMPTY_DEFAULT_SENTINEL = "__AUTOTEST_EMPTY_DEFAULT__";
    private static final ObjectMapper JSON = new ObjectMapper();

    public JmeterExtractionReporter() {
    }

    public JmeterExtractionReporter(List<JmeterExtractor> extractors) {
        setExtractors(extractors);
    }

    public void setExtractors(List<JmeterExtractor> extractors) {
        String value = java.util.stream.IntStream.range(0, extractors.size())
                .mapToObj(index -> {
                    JmeterExtractor item = extractors.get(index);
                    return index + "\u001f" + item.type() + "\u001f" + item.expression()
                            + "\u001f" + item.variable() + "\u001f"
                            + encodedDefaultValue(item)
                            + "\u001f" + defaultState(item)
                            + "\u001f" + item.failIfMissing()
                            + "\u001f" + item.defaultConfigured();
                })
                .reduce((left, right) -> left + "\u001e" + right)
                .orElse("");
        setProperty(DEFINITIONS, value);
    }

    @Override
    public void process() {
        SampleResult sample = JMeterContextService.getContext().getPreviousResult();
        if (sample == null) {
            return;
        }
        ArrayNode values = JsonNodeFactory.instance.arrayNode();
        for (String definition : getPropertyAsString(DEFINITIONS, "").split("\u001e")) {
            if (definition.isBlank()) {
                continue;
            }
            String[] fields = definition.split("\u001f", -1);
            if (fields.length != 8 && fields.length != 7 && fields.length != 6 && fields.length != 2) {
                continue;
            }
            int ruleIndex = fields.length >= 6 ? Integer.parseInt(fields[0]) : 0;
            if (fields.length >= 7) {
                ruleIndex = Integer.parseInt(fields[0]);
            }
            String type = fields.length >= 6 ? fields[1] : fields[0];
            String expression = fields.length >= 6 ? fields[2] : "";
            String variable = fields.length >= 6 ? fields[3] : fields[1];
            String defaultValue = fields.length >= 6 ? fields[4] : "";
            boolean newStateFormat = fields.length == 8 && isDefaultState(fields[5]);
            String state = newStateFormat ? fields[5] : "LEGACY";
            boolean failIfMissing;
            boolean defaultConfigured;
            boolean defaultIsNull;
            if (newStateFormat) {
                failIfMissing = Boolean.parseBoolean(fields[6]);
                defaultConfigured = !STATE_UNCONFIGURED.equals(state);
                defaultIsNull = STATE_NULL.equals(state);
                if (STATE_EMPTY.equals(state) && EMPTY_DEFAULT_SENTINEL.equals(defaultValue)) {
                    defaultValue = "";
                }
            } else {
                failIfMissing = fields.length >= 6 && Boolean.parseBoolean(fields[5]);
                defaultConfigured = fields.length == 8
                        ? Boolean.parseBoolean(fields[6]) : fields.length == 6 && !defaultValue.isBlank();
                if (fields.length == 7) {
                    defaultConfigured = Boolean.parseBoolean(fields[6]);
                }
                defaultIsNull = fields.length == 8 && Boolean.parseBoolean(fields[7]);
            }
            String raw = JMeterContextService.getContext().getVariables().get(variable);
            String matchNumber = JMeterContextService.getContext().getVariables().get(variable + "_matchNr");
            boolean matched = raw != null && (matchNumber == null || parseMatchNumber(matchNumber) > 0);
            boolean usedDefault = !matched && defaultConfigured;
            ObjectNode item = values.addObject();
            item.put("ruleIndex", ruleIndex);
            item.put("variable", variable);
            item.put("type", type);
            item.put("expression", expression);
            item.put("matched", matched);
            item.put("usedDefault", usedDefault);
            boolean failed = !matched && failIfMissing;
            item.put("failed", failed);
            String message = matched ? "已命中" : usedDefault ? "未命中，使用默认值" : "未命中";
            if (failed) {
                message += "；规则要求未命中时失败";
            }
            item.put("message", message);
            if (matched) {
                JsonNode typed = typedValue(type, raw);
                item.set("value", typed);
                item.put("valueType", valueType(typed));
            } else if (usedDefault) {
                JsonNode typed = defaultIsNull ? JsonNodeFactory.instance.nullNode() : typedValue(type, defaultValue);
                item.set("value", typed);
                item.put("valueType", valueType(typed));
            } else {
                item.put("valueType", "missing");
            }
        }
        if (values.isEmpty()) {
            return;
        }
        String encoded = Base64.getEncoder().encodeToString(values.toString().getBytes(StandardCharsets.UTF_8));
        String previous = sample.getResponseHeaders();
        sample.setResponseHeaders((previous == null || previous.isBlank() ? "" : previous + "\n")
                + HEADER + ": " + encoded);
    }

    private static int parseMatchNumber(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String defaultState(JmeterExtractor item) {
        if (!item.defaultConfigured()) return STATE_UNCONFIGURED;
        if (item.defaultValue() == null) return STATE_NULL;
        return item.defaultValue().isEmpty() ? STATE_EMPTY : STATE_VALUE;
    }

    private static String encodedDefaultValue(JmeterExtractor item) {
        if (item.defaultValue() != null && item.defaultValue().isEmpty()) {
            return EMPTY_DEFAULT_SENTINEL;
        }
        return item.defaultValue() == null ? "" : item.defaultValue();
    }

    private static boolean isDefaultState(String value) {
        return STATE_UNCONFIGURED.equals(value) || STATE_NULL.equals(value)
                || STATE_EMPTY.equals(value) || STATE_VALUE.equals(value);
    }

    private static JsonNode typedValue(String type, String value) {
        String trimmed = value.trim();
        if (type.equals("JSON_PATH") || type.equals("JMESPATH") || type.equals("JMES_PATH")) {
            try {
                JsonNode parsed = JSON.readTree(trimmed);
                return parsed == null || parsed.isMissingNode()
                        ? JsonNodeFactory.instance.textNode(value) : parsed;
            } catch (Exception ignored) {
                // 非 JSON 文本按 JMeter 变量原文记录。
            }
        }
        return JsonNodeFactory.instance.textNode(value);
    }

    private static String valueType(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        return value.getNodeType().name().toLowerCase(java.util.Locale.ROOT);
    }
}
