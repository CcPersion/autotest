package com.autotest.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiSpecValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsExplicitlyEmptyProxyOptions() throws Exception {
        var options = objectMapper.readTree("{\"proxy\":null}");

        assertDoesNotThrow(() -> ApiSpecValidator.validateOptions(
                UUID.randomUUID(), options, "requestSpec.options", null));
    }

    @Test
    void acceptsUrlEncodedEmptyAndDuplicateEntriesInWireOrder() throws Exception {
        var requestSpec = objectMapper.readTree("""
                {
                  "pathParams": [],
                  "query": [],
                  "headers": [],
                  "cookies": [],
                  "body": {"type":"URLENCODED","value":[
                    {"name":"tag","value":"first","enabled":true},
                    {"name":"empty","value":"","enabled":true},
                    {"name":"tag","value":"second","enabled":true}
                  ]},
                  "options": {}
                }
                """);

        assertDoesNotThrow(() -> ApiSpecValidator.validateDefinitionForExecution(
                UUID.randomUUID(), "POST", "/echo", requestSpec, null));
    }

    @Test
    void acceptsOnlyNarrowPositiveCaseTimeoutOverrides() throws Exception {
        var caseSpec = objectMapper.readTree("""
                {"pathParams":{},"query":{},"headers":{},"cookies":{},
                 "body":{"type":"JSON","value":{}},
                 "options":{"connectTimeoutMillis":1000,"readTimeoutMillis":2000,"totalTimeoutMillis":3000}}
                """);
        assertDoesNotThrow(() -> ApiSpecValidator.validateCase(
                UUID.randomUUID(), definition(), caseSpec, objectMapper.readTree("{}"),
                objectMapper.readTree("[]"), null));
    }

    @Test
    void rejectsZeroCaseTimeoutOverride() throws Exception {
        var caseSpec = objectMapper.readTree("""
                {"pathParams":{},"query":{},"headers":{},"cookies":{},
                 "body":{"type":"JSON","value":{}},
                 "options":{"totalTimeoutMillis":0}}
                """);
        assertThrows(RuntimeException.class, () -> ApiSpecValidator.validateCase(
                UUID.randomUUID(), definition(), caseSpec, objectMapper.readTree("{}"),
                objectMapper.readTree("[]"), null));
    }

    @Test
    void acceptsDataRowsAndOptionsInCaseSpec() throws Exception {
        var caseSpec = objectMapper.readTree("""
                {
                  "pathParams": {},
                  "query": {},
                  "headers": {},
                  "cookies": {},
                  "body": {"type":"JSON","value":{}},
                  "extractors": [],
                  "dataRows": [
                    {"id":"row-1","enabled":true,"values":{"userId":"1001","enabled":true}},
                    {"id":"row-2","enabled":false,"values":{"userId":"1002"}}
                  ],
                  "dataRowOptions": {"continueOnFailure": false}
                }
                """);
        var definition = definition();

        assertDoesNotThrow(() -> ApiSpecValidator.validateCase(
                UUID.randomUUID(), definition, caseSpec, objectMapper.readTree("{}"),
                objectMapper.readTree("[]"), null));
    }

    @Test
    void rejectsUnknownDataRowFieldAndNonScalarValue() throws Exception {
        var definition = definition();
        var unknownField = objectMapper.readTree("""
                {"pathParams":{},"query":{},"headers":{},"cookies":{},
                 "body":{"type":"JSON","value":{}},"extractors":[],
                 "dataRows":[{"id":"row-1","values":{},"unexpected":true}]}
                """);
        var objectValue = objectMapper.readTree("""
                {"pathParams":{},"query":{},"headers":{},"cookies":{},
                 "body":{"type":"JSON","value":{}},"extractors":[],
                 "dataRows":[{"id":"row-1","values":{"profile":{"name":"A"}}}]}
                """);

        assertThrows(RuntimeException.class, () -> ApiSpecValidator.validateCase(
                UUID.randomUUID(), definition, unknownField, objectMapper.readTree("{}"),
                objectMapper.readTree("[]"), null));
        assertThrows(RuntimeException.class, () -> ApiSpecValidator.validateCase(
                UUID.randomUUID(), definition, objectValue, objectMapper.readTree("{}"),
                objectMapper.readTree("[]"), null));
    }

    @Test
    void acceptsNewStructuredAssertionContractAndKeepsLegacyHeaderCompatibility() throws Exception {
        var caseSpec = caseSpecWithExtractors();
        var assertions = objectMapper.readTree("""
                [
                  {"type":"JSON_PATH","expression":"$.count","operator":"GREATER_THAN","expected":5},
                  {"type":"JMES_PATH","expression":"count","operator":"LESS_THAN","expected":10},
                  {"type":"HEADER","expression":"X-Trace-Id","operator":"EXISTS"},
                  {"type":"COOKIE","expression":"sid","operator":"CONTAINS","expected":"cookie"},
                  {"type":"VARIABLE","expression":"token","operator":"EQUALS","expected":"abc"},
                  {"type":"HEADER","expression":"X-Legacy","operator":"CONTAINS","expected":"legacy"}
                ]
                """);

        assertDoesNotThrow(() -> ApiSpecValidator.validateCase(UUID.randomUUID(), definition(), caseSpec,
                objectMapper.readTree("{}"), assertions, null));
    }

    @Test
    void rejectsInvalidNewAssertionOperatorsAndRemoteSchema() throws Exception {
        var caseSpec = caseSpecWithExtractors();
        assertThrows(RuntimeException.class, () -> ApiSpecValidator.validateCase(UUID.randomUUID(), definition(),
                caseSpec, objectMapper.readTree("{}"),
                objectMapper.readTree("[{\"type\":\"VARIABLE\",\"expression\":\"token\",\"operator\":\"MATCHES\",\"expected\":\"x\"}]"), null));
        assertThrows(RuntimeException.class, () -> ApiSpecValidator.validateCase(UUID.randomUUID(), definition(),
                caseSpec, objectMapper.readTree("{}"),
                objectMapper.readTree("[{\"type\":\"HEADER\",\"operator\":\"EXISTS\"}]"), null));
        assertThrows(RuntimeException.class, () -> ApiSpecValidator.validateCase(UUID.randomUUID(), definition(),
                caseSpec, objectMapper.readTree("{}"),
                objectMapper.readTree("[{\"type\":\"HEADER\",\"operator\":\"CONTAINS\",\"expected\":\"legacy\"}]"), null));
        assertThrows(RuntimeException.class, () -> ApiSpecValidator.validateCase(UUID.randomUUID(), definition(),
                caseSpec, objectMapper.readTree("{}"),
                objectMapper.readTree("[{\"type\":\"COOKIE\",\"operator\":\"EQUALS\",\"expected\":\"legacy\"}]"), null));
        assertThrows(RuntimeException.class, () -> ApiSpecValidator.validateCase(UUID.randomUUID(), definition(),
                caseSpec, objectMapper.readTree("{}"),
                objectMapper.readTree("[{\"type\":\"SCHEMA\",\"operator\":\"VALIDATE\",\"expected\":{\"$ref\":\"https://evil.test/schema\"}}]"), null));
        assertThrows(RuntimeException.class, () -> ApiSpecValidator.validateCase(UUID.randomUUID(), definition(),
                caseSpec, objectMapper.readTree("{}"),
                objectMapper.readTree("[{\"type\":\"BODY\",\"operator\":\"MATCHES\",\"expected\":\"(a+)+$\"}]"), null));
    }

    @Test
    void rejectsAssertionExpectedTypesThatDoNotMatchTheOperator() throws Exception {
        var caseSpec = caseSpecWithExtractors();
        assertThrows(RuntimeException.class, () -> ApiSpecValidator.validateCase(UUID.randomUUID(), definition(),
                caseSpec, objectMapper.readTree("{}"),
                objectMapper.readTree("[{\"type\":\"JSON_PATH\",\"expression\":\"$.count\",\"operator\":\"GREATER_THAN\",\"expected\":\"5\"}]"), null));
        assertThrows(RuntimeException.class, () -> ApiSpecValidator.validateCase(UUID.randomUUID(), definition(),
                caseSpec, objectMapper.readTree("{}"),
                objectMapper.readTree("[{\"type\":\"JMES_PATH\",\"expression\":\"count\",\"operator\":\"LESS_THAN\",\"expected\":true}]"), null));
        assertThrows(RuntimeException.class, () -> ApiSpecValidator.validateCase(UUID.randomUUID(), definition(),
                caseSpec, objectMapper.readTree("{}"),
                objectMapper.readTree("[{\"type\":\"VARIABLE\",\"expression\":\"count\",\"operator\":\"GREATER_THAN\",\"expected\":\"5\"}]"), null));
        assertThrows(RuntimeException.class, () -> ApiSpecValidator.validateCase(UUID.randomUUID(), definition(),
                caseSpec, objectMapper.readTree("{}"),
                objectMapper.readTree("[{\"type\":\"BODY\",\"operator\":\"MATCHES\",\"expected\":5}]"), null));
        assertThrows(RuntimeException.class, () -> ApiSpecValidator.validateCase(UUID.randomUUID(), definition(),
                caseSpec, objectMapper.readTree("{}"),
                objectMapper.readTree("[{\"type\":\"HEADER\",\"expression\":\"X-Trace\",\"operator\":\"MATCHES\",\"expected\":false}]"), null));
    }

    @Test
    void keepsLegacyHeaderWithoutExpressionOnlyForExistingAssetValidation() throws Exception {
        var caseSpec = caseSpecWithExtractors();
        var legacy = objectMapper.readTree("[{\"type\":\"HEADER\",\"operator\":\"CONTAINS\",\"expected\":\"legacy\"}]");

        assertDoesNotThrow(() -> ApiSpecValidator.validateCase(UUID.randomUUID(), definition(), caseSpec,
                objectMapper.readTree("{}"), legacy, null, true));
    }

    private com.fasterxml.jackson.databind.JsonNode caseSpecWithExtractors() throws Exception {
        return objectMapper.readTree("""
                {"pathParams":{"orderId":"1"},"query":{},"headers":{},"cookies":{},
                 "body":{"type":"JSON","value":{}},
                 "extractors":[{"type":"JSON_PATH","expression":"$.count","variable":"count"}]}
                """);
    }

    private ApiDefinitionRecord definition() throws Exception {
        return new ApiDefinitionRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Orders",
                "POST", "/orders/{orderId}", objectMapper.readTree("""
                {"pathParams":[{"name":"orderId","value":"1001"}],"query":[],"headers":[],
                 "cookies":[],"body":{"type":"JSON","value":{}},"options":{}}
                """), 0, false, null, null);
    }
}
