package com.autotest.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtractorTrialServiceTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void evaluatesAllRulesAndKeepsMissSeparateFromDefault() throws Exception {
        ExtractorTrialRequest request = new ExtractorTrialRequest(new ExtractorTrialSample(200, 12L,
                "{\"profile\":{\"roles\":[\"qa\"]}}", Map.of("X-Trace-Id", "trace-1"), Map.of("sid", "cookie-1")),
                List.of(
                        new ExtractorTrialRule("JSON_PATH", "$.profile", "profile", null, true),
                        new ExtractorTrialRule("JMESPATH", "profile.roles", "roles", null, true),
                        new ExtractorTrialRule("HEADER", "x-trace-id", "trace", null, true),
                        new ExtractorTrialRule("JSON_PATH", "$.missing", "fallback", json.readTree("42"), true)));

        List<ExtractorTrialResult> results = new ExtractorTrialService().evaluate(request);

        assertEquals(4, results.size());
        assertEquals(0, results.get(0).ruleIndex());
        assertEquals("object", results.get(0).valueType());
        assertEquals("array", results.get(1).valueType());
        assertEquals("trace-1", results.get(2).value().asText());
        assertFalse(results.get(3).matched());
        assertTrue(results.get(3).usedDefault());
        assertTrue(results.get(3).failed());
    }

    @Test
    void keepsJmeterJmesFilterAndExplicitNullOrEmptyDefaultsInTrialFacts() throws Exception {
        ExtractorTrialRequest request = new ExtractorTrialRequest(new ExtractorTrialSample(200, 12L,
                "{\"items\":[{\"enabled\":true,\"id\":\"first\"},{\"enabled\":false,\"id\":\"second\"}]}",
                Map.of(), Map.of()),
                List.of(
                        new ExtractorTrialRule("JMESPATH", "items[?enabled].id | [0]", "first", null, true),
                        new ExtractorTrialRule("JSON_PATH", "$.missing", "nullDefault", json.readTree("null"), false),
                        new ExtractorTrialRule("JSON_PATH", "$.missing", "emptyDefault", json.readTree("\"\""), false)));

        List<ExtractorTrialResult> results = new ExtractorTrialService().evaluate(request);

        assertEquals("first", results.get(0).value().asText());
        assertFalse(results.get(1).matched());
        assertTrue(results.get(1).usedDefault());
        assertEquals("null", results.get(1).valueType());
        assertTrue(results.get(2).usedDefault());
        assertEquals("string", results.get(2).valueType());
        assertEquals("", results.get(2).value().asText());
    }

    @Test
    void returnsOneInvalidRuleWithoutPreventingOtherRules() {
        ExtractorTrialRequest request = new ExtractorTrialRequest(new ExtractorTrialSample(200, 0L, "not-json",
                Map.of(), Map.of()), List.of(
                        new ExtractorTrialRule("REGEX", "[", "bad", null, false),
                        new ExtractorTrialRule("REGEX", "ok", "good", null, false)));

        List<ExtractorTrialResult> results = new ExtractorTrialService().evaluate(request);

        assertEquals(2, results.size());
        assertEquals("INVALID_EXPRESSION", results.get(0).errorCode());
        assertFalse(results.get(1).failed());
    }

    @Test
    void rejectsInvalidRuleShapeAsBadRequestInsteadOfReturningSuccessItem() {
        ExtractorTrialRequest request = new ExtractorTrialRequest(new ExtractorTrialSample(200, 0L, "{}",
                Map.of(), Map.of()), List.of(
                new ExtractorTrialRule("SCRIPT", "$.value", "value", null, true)));

        var error = assertThrows(com.autotest.platform.security.ApiDomainException.class,
                () -> new ExtractorTrialService().evaluate(request));
        assertEquals(400, error.status());
    }

    @Test
    void rejectsOversizedExpressionAsBadRequest() {
        ExtractorTrialRequest request = new ExtractorTrialRequest(new ExtractorTrialSample(200, 0L, "{}",
                Map.of(), Map.of()), List.of(new ExtractorTrialRule("REGEX",
                "a".repeat(4097), "value", null, true)));

        var error = assertThrows(com.autotest.platform.security.ApiDomainException.class,
                () -> new ExtractorTrialService().evaluate(request));
        assertEquals(400, error.status());
    }

    @Test
    void rejectsRegexBeyondSafePatternLengthAsBadRequest() {
        ExtractorTrialRequest request = new ExtractorTrialRequest(new ExtractorTrialSample(200, 0L, "{}",
                Map.of(), Map.of()), List.of(new ExtractorTrialRule("REGEX",
                "a".repeat(2049), "value", null, true)));

        var error = assertThrows(com.autotest.platform.security.ApiDomainException.class,
                () -> new ExtractorTrialService().evaluate(request));
        assertEquals(400, error.status());
    }
}
