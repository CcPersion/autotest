package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunVariableContextTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void resolvesFiveScopesWithTypedValuesAndLegacyVariablesAlias() throws Exception {
        RunVariableContext context = RunVariableContext.fromPlan("run-1", json.readTree("""
                {
                  "variables":{"value":"case","typed":7},
                  "variableScopes":{
                    "environment":{"value":"environment"},
                    "scenario":{"value":"scenario"},
                    "caseVariables":{"value":"caseVariables"},
                    "dataRow":{"value":"dataRow"},
                    "extracted":{"value":"extracted"}
                  }
                }
                """));

        assertEquals("extracted", context.resolveText("${value}"));
        assertEquals(7, context.resolveNode(TextNode.valueOf("${typed}")).asInt());
        assertEquals("dataRow", context.withExtractedCleared().resolveText("${value}"));
    }

    @Test
    void keepsCompleteJsonTypeAndStringifiesMixedText() throws Exception {
        RunVariableContext context = RunVariableContext.builder("run-1")
                .dataRow(Map.of("count", json.readTree("7"), "enabled", json.readTree("true")))
                .build();

        JsonNode resolved = context.resolveNode(json.readTree("{\"count\":\"${count}\",\"label\":\"item-${count}\"}"));

        assertTrue(resolved.get("count").isIntegralNumber());
        assertEquals(7, resolved.get("count").asInt());
        assertEquals("item-7", resolved.get("label").asText());
    }

    @Test
    void supportsFrozenFunctionsAndRejectsSecretWrites() {
        RunVariableContext context = RunVariableContext.builder("run-1").build();

        String value = context.resolveText("{{$uuid}}|{{$timestamp}}|{{$date}}|{{$formatdate:yyyy-MM-dd}}|{{$randomint:0,2147483647}}|{{$randomstring:4}}");

        String[] parts = value.split("\\|");
        assertEquals(6, parts.length);
        assertTrue(parts[0].matches("[0-9a-f-]{36}"));
        assertTrue(parts[2].matches("\\d{4}-\\d{2}-\\d{2}"));
        assertTrue(parts[3].matches("\\d{4}-\\d{2}-\\d{2}"));
        assertTrue(parts[5].matches("[A-Za-z0-9]{4}"));
        assertThrows(IllegalArgumentException.class, () -> RunVariableContext.validateWritableName("secret:token"));
        assertThrows(IllegalArgumentException.class, () -> context.resolveText("{{$randomint:2,1}}"));
    }

    @Test
    void rejectsUppercaseBuiltinNamesAndRandomIntOutsideContractRange() {
        RunVariableContext context = RunVariableContext.builder("run-1").build();

        assertThrows(IllegalArgumentException.class, () -> context.resolveText("{{$UUID}}"));
        assertThrows(IllegalArgumentException.class, () -> context.resolveText("{{$Timestamp}}"));
        assertDoesNotThrow(() -> context.resolveText("{{$formatdate:YYYY-MM-dd}}"));
        assertThrows(IllegalArgumentException.class, () -> context.resolveText("{{$FORMATDATE:yyyy-MM-dd}}"));
        assertThrows(IllegalArgumentException.class, () -> context.resolveText("{{$formatdate: }}"));
        assertThrows(IllegalArgumentException.class, () -> context.resolveText("{{$formatdate:yyyy-MM-dd[}}"));
        assertThrows(IllegalArgumentException.class, () -> context.resolveText("{{$formatdate:yyyy-MM-dd]}}"));
        assertThrows(IllegalArgumentException.class, () -> context.resolveText("{{$randomint:-1,0}}"));
        assertThrows(IllegalArgumentException.class, () -> context.resolveText("{{$randomint:0,2147483648}}"));
        assertThrows(IllegalArgumentException.class, () -> context.resolveText("{{$randomint:2147483648,2147483648}}"));
    }

    @Test
    void rowContextsAndParallelRunsDoNotShareExtractedValues() throws Exception {
        RunVariableContext rowOne = RunVariableContext.builder("run-1", "row-1")
                .dataRow(Map.of("id", json.readTree("1"))).build();
        RunVariableContext rowTwo = RunVariableContext.builder("run-1", "row-2")
                .dataRow(Map.of("id", json.readTree("2"))).build();
        rowOne.putExtracted("token", json.readTree("\"one\""));
        rowTwo.putExtracted("token", json.readTree("\"two\""));

        var pool = Executors.newFixedThreadPool(2);
        try {
            var values = pool.invokeAll(java.util.List.<Callable<String>>of(
                    () -> rowOne.resolveText("${id}:${token}"),
                    () -> rowTwo.resolveText("${id}:${token}")));
            assertEquals("1:one", values.get(0).get());
            assertEquals("2:two", values.get(1).get());
        } finally {
            pool.shutdownNow();
        }
        rowOne.clearExtracted();
        assertThrows(RunVariableContext.VariableUndefinedException.class,
                () -> rowOne.resolveText("${token}"));
    }

    @Test
    void loopOverlayHasHighestPriorityAndRestoresPreviousValue() throws Exception {
        RunVariableContext context = RunVariableContext.builder("run-1")
                .caseVariables(Map.of("item", json.readTree("\"case\""))).build();
        context.putExtracted("item", json.readTree("\"extracted\""));

        try (RunVariableContext.Overlay ignored = context.pushOverlay(Map.of("item", json.readTree("\"loop\"")))) {
            assertEquals("loop", context.resolveText("${item}"));
        }
        assertEquals("extracted", context.resolveText("${item}"));
    }

    @Test
    void preflightReportsRedactedPathAndUndefinedCodeWithoutTouchingExternalSystems() throws Exception {
        RunVariableContext context = RunVariableContext.builder("run-1").build();

        RunVariableContext.PreflightResult result = context.preflight(
                json.readTree("{\"urlTemplate\":\"/orders/${missing}\",\"headers\":[{\"name\":\"X-Test\",\"value\":\"${alsoMissing}\"}]}"),
                "requestSpec");

        assertFalse(result.valid());
        assertEquals("VARIABLE_UNDEFINED", result.code());
        assertTrue(result.path().startsWith("requestSpec"));
        assertFalse(result.path().contains("missing-secret-value"));
    }

    @Test
    void scopeSerializationRoundTripsWithoutSecretValues() throws Exception {
        RunVariableContext context = RunVariableContext.builder("run-1")
                .environment(Map.of("host", json.readTree("\"env\"")))
                .caseVariables(Map.of("count", json.readTree("3")))
                .build();

        JsonNode serialized = context.serializeScopes(json);
        RunVariableContext restored = RunVariableContext.fromScopes("run-1", serialized);

        assertEquals("env", restored.resolveText("${host}"));
        assertEquals(3, restored.resolveNode(TextNode.valueOf("${count}")).asInt());
        assertFalse(serialized.toString().contains("secret"));
    }
}
