package com.autotest.runner;

import com.autotest.contracts.extraction.AssertionRule;
import com.autotest.contracts.extraction.ControlledAssertionEvaluator;
import com.autotest.contracts.extraction.ControlledAssertionResult;
import com.autotest.contracts.extraction.HttpResponseSample;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.testelement.AbstractTestElement;

import java.util.LinkedHashMap;
import java.util.Map;

/** 受控声明式断言组件；不执行脚本、表达式引擎或用户代码。 */
public final class JmeterControlledAssertion extends AbstractTestElement implements Assertion {

    private static final String TYPE = "autotest.assertion.type";
    private static final String OPERATOR = "autotest.assertion.operator";
    private static final String EXPRESSION = "autotest.assertion.expression";
    private static final String EXPECTED = "autotest.assertion.expected";
    private static final ObjectMapper JSON = new ObjectMapper();

    public void setRule(JmeterAssertion input) {
        setProperty(TYPE, input.type());
        setProperty(OPERATOR, input.operator());
        setProperty(EXPRESSION, input.expression());
        if (input.expected() == null) {
            removeProperty(EXPECTED);
        } else {
            setProperty(EXPECTED, input.expected().toString());
        }
    }

    @Override
    public AssertionResult getResult(SampleResult sampleResult) {
        AssertionResult result = new AssertionResult(getName());
        try {
            AssertionRule rule = new AssertionRule(0, getPropertyAsString(TYPE),
                    getPropertyAsString(OPERATOR), getPropertyAsString(EXPRESSION, ""), expected());
            ControlledAssertionResult evaluated = ControlledAssertionEvaluator.evaluate(
                    rule, response(sampleResult), variables(rule));
            if (!evaluated.passed()) {
                result.setFailure(true);
                result.setFailureMessage(evaluated.message());
            }
        } catch (Exception exception) {
            result.setError(true);
            result.setFailureMessage("受控断言求值失败");
        }
        return result;
    }

    private JsonNode expected() throws Exception {
        String value = getPropertyAsString(EXPECTED, "");
        return value.isBlank() ? null : JSON.readTree(value);
    }

    private static Map<String, JsonNode> variables(AssertionRule rule) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        var context = JMeterContextService.getContext();
        JMeterVariables variables = context == null ? null : context.getVariables();
        if (variables == null || !"VARIABLE".equals(rule.type())) return result;
        String value = variables.get(rule.expression());
        if (value != null) result.put(rule.expression(), parseVariable(value));
        return result;
    }

    private static JsonNode parseVariable(String value) {
        try {
            JsonNode parsed = JSON.readTree(value);
            return parsed == null ? TextNode.valueOf(value) : parsed;
        } catch (Exception ignored) {
            return TextNode.valueOf(value);
        }
    }

    private static HttpResponseSample response(SampleResult sample) {
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> cookies = new LinkedHashMap<>();
        String rawHeaders = sample.getResponseHeaders() == null ? "" : sample.getResponseHeaders();
        for (String line : rawHeaders.split("\\R")) {
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            String name = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            headers.put(name, value);
            if ("Set-Cookie".equalsIgnoreCase(name)) {
                int equals = value.indexOf('=');
                if (equals > 0) {
                    String cookieName = value.substring(0, equals).trim();
                    int end = value.indexOf(';', equals + 1);
                    cookies.put(cookieName, value.substring(equals + 1, end < 0 ? value.length() : end));
                }
            }
        }
        return new HttpResponseSample(responseCode(sample), sample.getTime(),
                sample.getResponseDataAsString(), headers, cookies);
    }

    private static int responseCode(SampleResult sample) {
        try {
            return Integer.parseInt(sample.getResponseCode());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
