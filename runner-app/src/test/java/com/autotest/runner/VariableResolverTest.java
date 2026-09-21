package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariableResolverTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void usesExtractedThenDataRowThenCaseThenScenarioThenEnvironment() {
        VariableResolver resolver = new VariableResolver(new VariableResolver.Scopes(
                Map.of("shared", json.getNodeFactory().textNode("environment")),
                Map.of("shared", json.getNodeFactory().textNode("scenario")),
                Map.of("shared", json.getNodeFactory().textNode("case")),
                Map.of("shared", json.getNodeFactory().textNode("row")),
                Map.of("shared", json.getNodeFactory().textNode("extracted"))));

        assertEquals("extracted", resolver.resolveText("${shared}"));
    }

    @Test
    void keepsJsonTypesForWholeVariableAndInterpolatesTextForMixedValues() throws Exception {
        VariableResolver resolver = new VariableResolver(new VariableResolver.Scopes(
                Map.of(), Map.of(), Map.of(), Map.of("count", json.readTree("7")), Map.of()));

        JsonNode result = resolver.resolveNode(json.readTree("{\"count\":\"${count}\",\"label\":\"item-${count}\"}"));

        assertTrue(result.get("count").isIntegralNumber());
        assertEquals(7, result.get("count").asInt());
        assertEquals("item-7", result.get("label").asText());
    }

    @Test
    void evaluatesBuiltinsAndRejectsUndefinedVariables() {
        VariableResolver resolver = new VariableResolver(new VariableResolver.Scopes(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));

        String value = resolver.resolveText("{{$randomint:1,1}}/{{$randomstring:4}}/{{$uuid}}");

        assertTrue(value.matches("1/[A-Za-z0-9]{4}/[0-9a-f-]{36}"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveText("${missing}"));
    }

    @Test
    void keepsSecretReferenceOpaqueUntilRunnerSecretResolverIsConnected() {
        VariableResolver resolver = new VariableResolver(new VariableResolver.Scopes(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));

        assertEquals("Bearer ${secret:token}", resolver.resolveText("Bearer ${secret:token}"));
    }
}
