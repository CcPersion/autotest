package com.autotest.contracts.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledAssertionEvaluatorTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void supportsJsonNumericComparisonsAndVariableAssertions() throws Exception {
        HttpResponseSample response = new HttpResponseSample(200, 25,
                "{\"count\":7,\"tags\":[\"smoke\",\"api\"]}",
                Map.of("X-Trace-Id", "trace-1"), Map.of("sid", "cookie-1"));
        Map<String, com.fasterxml.jackson.databind.JsonNode> variables = Map.of("limit", json.readTree("10"));

        assertTrue(ControlledAssertionEvaluator.evaluate(
                new AssertionRule(0, "JSON_PATH", "GREATER_THAN", "$.count", json.readTree("5")), response, variables).passed());
        assertTrue(ControlledAssertionEvaluator.evaluate(
                new AssertionRule(1, "JMES_PATH", "LESS_THAN", "count", json.readTree("10")), response, variables).passed());
        assertTrue(ControlledAssertionEvaluator.evaluate(
                new AssertionRule(2, "VARIABLE", "EQUALS", "limit", json.readTree("10")), response, variables).passed());
    }

    @Test
    void targetsHeaderAndCookieByExpressionAndReportsEveryFailure() throws Exception {
        HttpResponseSample response = new HttpResponseSample(200, 25, "ok",
                Map.of("X-Trace-Id", "trace-1"), Map.of("sid", "cookie-1"));
        List<ControlledAssertionResult> results = List.of(
                ControlledAssertionEvaluator.evaluate(new AssertionRule(3, "HEADER", "EQUALS", "X-Trace-Id", TextNode.valueOf("trace-1")), response, Map.of()),
                ControlledAssertionEvaluator.evaluate(new AssertionRule(4, "COOKIE", "CONTAINS", "sid", TextNode.valueOf("cookie")), response, Map.of()),
                ControlledAssertionEvaluator.evaluate(new AssertionRule(5, "BODY", "CONTAINS", "", TextNode.valueOf("missing")), response, Map.of()));

        assertTrue(results.get(0).passed());
        assertTrue(results.get(1).passed());
        assertFalse(results.get(2).passed());
        assertTrue(results.stream().allMatch(item -> item.ruleIndex() >= 3));
    }

    @Test
    void rejectsRemoteSchemaReferencesAndCatastrophicRegex() throws Exception {
        HttpResponseSample response = new HttpResponseSample(200, 25, "aaaaaaaaaaaaaaaaaaaa",
                Map.of(), Map.of());
        AssertionRule regex = new AssertionRule(1, "BODY", "MATCHES", "",
                TextNode.valueOf("a+$"));

        ControlledAssertionResult regexResult = ControlledAssertionEvaluator.evaluate(regex, response, Map.of());

        assertThrows(IllegalArgumentException.class, () -> new AssertionRule(0, "SCHEMA", "VALIDATE", "",
                json.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"$ref\":\"http://evil.test/x\"}}}")));
        assertTrue(regexResult.passed());
        assertEquals("", regexResult.errorCode());
    }

    @Test
    void rejectsDangerousRegexFeaturesBeforeMatchingAndDoesNotOverflow() {
        assertThrows(IllegalArgumentException.class, () -> SafeRegex.compile("(a|aa)+$"));
        assertThrows(IllegalArgumentException.class, () -> SafeRegex.compile("(a+)+$"));
        assertThrows(IllegalArgumentException.class, () -> SafeRegex.compile("a{0,64}a{0,64}"));
        assertThrows(IllegalArgumentException.class, () -> SafeRegex.compile("(?=a)a"));
        assertThrows(IllegalArgumentException.class, () -> SafeRegex.compile("(a)\\1"));
        assertThrows(IllegalArgumentException.class, () -> SafeRegex.compile("a".repeat(SafeRegex.MAX_LENGTH + 1)));
    }

    @Test
    void turnsLongOverlappingRegexIntoControlledFailureWithoutRunningPattern() {
        ControlledExtractionResult result = ControlledExtractionEvaluator.extract(0,
                new ExtractionRule("REGEX", "(a|aa)+$", "value", null, true),
                new HttpResponseSample(200, 0, "a".repeat(200_000) + "!", Map.of(), Map.of()));

        assertTrue(result.failed());
        assertEquals("INVALID_EXPRESSION", result.errorCode());
    }
}
