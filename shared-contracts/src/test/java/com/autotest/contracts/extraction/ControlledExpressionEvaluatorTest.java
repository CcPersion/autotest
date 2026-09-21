package com.autotest.contracts.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledExpressionEvaluatorTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void evaluatesSixHttpExtractorTypesWithStableTypedValues() throws Exception {
        String body = "{\"data\":{\"token\":\"abc\",\"items\":[{\"id\":7},{\"id\":8}],\"enabled\":true}}";
        HttpResponseSample response = new HttpResponseSample(201, 42, body,
                Map.of("X-Trace-Id", "trace-1"), Map.of("sid", "cookie-1"));

        assertEquals("abc", extract("JSON_PATH", "$.data.token", response).value().textValue());
        assertEquals(7, extract("JMESPATH", "data.items[0].id", response).value().intValue());
        assertEquals("abc", extract("XPATH", "//data/token/text()", new HttpResponseSample(
                200, 1, "<data><token>abc</token></data>", Map.of(), Map.of())).value().textValue());
        assertEquals("abc", extract("REGEX", "token=([^&]+)",
                new HttpResponseSample(200, 1, "token=abc", Map.of(), Map.of())).value().textValue());
        assertEquals("trace-1", extract("HEADER", "x-trace-id", response).value().textValue());
        assertEquals("cookie-1", extract("COOKIE", "sid", response).value().textValue());
    }

    @Test
    void evaluatesJmesPathFilterAndPipeUsedByJmeter() throws Exception {
        HttpResponseSample response = new HttpResponseSample(200, 1,
                "{\"items\":[{\"enabled\":true,\"id\":\"first\"},{\"enabled\":false,\"id\":\"second\"}]}",
                Map.of(), Map.of());

        ControlledExtractionResult result = extract("JMESPATH", "items[?enabled].id | [0]", response);

        assertTrue(result.matched(), result.message());
        assertEquals("first", result.value().textValue());
    }

    @Test
    void evaluatesJmesPathBooleanNumberAndStringLiteralComparisons() throws Exception {
        HttpResponseSample response = new HttpResponseSample(200, 1,
                "{\"items\":["
                        + "{\"enabled\":true,\"count\":2,\"name\":\"first\",\"id\":\"one\"},"
                        + "{\"enabled\":false,\"count\":3,\"name\":\"second\",\"id\":\"two\"}]}",
                Map.of(), Map.of());

        ControlledExtractionResult booleanLiteral = extract(
                "JMESPATH", "items[?enabled == `true`].id | [0]", response);
        ControlledExtractionResult numberLiteral = extract(
                "JMESPATH", "items[?count == `2`].id | [0]", response);
        ControlledExtractionResult stringLiteral = extract(
                "JMESPATH", "items[?name == `\"first\"`].id | [0]", response);

        assertTrue(booleanLiteral.matched(), booleanLiteral.message());
        assertTrue(numberLiteral.matched(), numberLiteral.message());
        assertTrue(stringLiteral.matched(), stringLiteral.message());
        assertEquals("one", booleanLiteral.value().textValue());
        assertEquals("one", numberLiteral.value().textValue());
        assertEquals("one", stringLiteral.value().textValue());
    }

    @Test
    void rejectsJmesPathExpressionsOutsideTheControlledComparisonSubset() throws Exception {
        HttpResponseSample response = new HttpResponseSample(200, 1,
                "{\"items\":[{\"enabled\":true,\"id\":\"one\"}]}", Map.of(), Map.of());

        ControlledExtractionResult result = extract("JMESPATH", "items[?enabled == true || foo].id | [0]", response);

        assertTrue(result.failed());
        assertEquals("INVALID_EXPRESSION", result.errorCode());
    }

    @Test
    void distinguishesMissFromDefaultAndPreservesNullObjectAndArray() throws Exception {
        HttpResponseSample response = new HttpResponseSample(200, 3,
                "{\"object\":{\"id\":1},\"array\":[1,2],\"nullValue\":null}",
                Map.of(), Map.of());

        ControlledExtractionResult object = extract("JSON_PATH", "$.object", response);
        ControlledExtractionResult array = extract("JSON_PATH", "$.array", response);
        ControlledExtractionResult nullValue = extract("JSON_PATH", "$.nullValue", response);
        ControlledExtractionResult missing = ControlledExpressionEvaluator.extract(0,
                new ExtractionRule("JSON_PATH", "$.missing", "fallback", json.readTree("42"), true), response);

        assertTrue(object.matched());
        assertEquals("object", object.valueType());
        assertTrue(array.matched());
        assertEquals("array", array.valueType());
        assertTrue(nullValue.matched());
        assertEquals("null", nullValue.valueType());
        assertFalse(missing.matched());
        assertTrue(missing.usedDefault());
        assertEquals("number", missing.valueType());
        assertTrue(missing.failed());
    }

    @Test
    void reportsInvalidRegexAndRejectsUnsafeOrOversizedXpath() {
        HttpResponseSample response = new HttpResponseSample(200, 1, "body", Map.of(), Map.of());
        ControlledExtractionResult invalidRegex = ControlledExpressionEvaluator.extract(0,
                new ExtractionRule("REGEX", "[", "value", null, false), response);
        ControlledExtractionResult unsafeXpath = ControlledExpressionEvaluator.extract(1,
                new ExtractionRule("XPATH", "//x", "value", null, false), new HttpResponseSample(
                        200, 1, "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>",
                        Map.of(), Map.of()));

        assertTrue(invalidRegex.failed());
        assertEquals("INVALID_EXPRESSION", invalidRegex.errorCode());
        assertTrue(unsafeXpath.failed());
        assertEquals("INVALID_EXPRESSION", unsafeXpath.errorCode());
    }

    private ControlledExtractionResult extract(String type, String expression, HttpResponseSample response) {
        return ControlledExpressionEvaluator.extract(0,
                new ExtractionRule(type, expression, "value", null, true), response);
    }
}
