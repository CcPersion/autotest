package com.autotest.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcSqlExecutorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void writePlanWithoutConfirmationFailsBeforeConnection() throws Exception {
        var plan = json.readTree("{\"stepId\":\"step-1\",\"databaseType\":\"POSTGRESQL\",\"host\":\"localhost\",\"port\":5432,\"databaseName\":\"db\",\"username\":\"u\",\"credentialRef\":\"${secret:db}\",\"sql\":\"DELETE FROM t\",\"allowWrite\":false,\"confirmed\":false}");
        assertThrows(IllegalArgumentException.class, () -> new JdbcSqlExecutor().execute(plan, null));
    }

    @Test
    void bindsVariablesWithExtractedValueWinningOverRowScenarioAndEnvironment() throws Exception {
        var plan = json.readTree("{\"stepId\":\"step-1\",\"databaseType\":\"POSTGRESQL\",\"host\":\"localhost\",\"port\":5432,\"databaseName\":\"db\",\"username\":\"u\",\"credentialRef\":\"${secret:db}\",\"sql\":\"SELECT ${shared}\",\"variableScopes\":{\"environment\":{\"shared\":\"env\"},\"scenario\":{\"shared\":\"scenario\"},\"dataRow\":{\"shared\":\"row\"},\"extracted\":{\"shared\":\"plan-extracted\"}}}");
        Map<String, com.fasterxml.jackson.databind.JsonNode> extracted = new LinkedHashMap<>();
        extracted.put("shared", json.getNodeFactory().textNode("step-extracted"));

        var method = JdbcSqlExecutor.class.getDeclaredMethod("bind", String.class, com.fasterxml.jackson.databind.JsonNode.class, Map.class);
        method.setAccessible(true);
        var bound = method.invoke(new JdbcSqlExecutor(), "SELECT ${shared}", plan.path("variableScopes"), extracted);

        var valuesAccessor = bound.getClass().getDeclaredMethod("values");
        valuesAccessor.setAccessible(true);
        var values = (java.util.List<?>) valuesAccessor.invoke(bound);
        assertEquals("step-extracted", ((com.fasterxml.jackson.databind.JsonNode) values.get(0)).asText());
    }

    @Test
    void bindsSqlThroughRunVariableContextAndRejectsUndefinedBeforeConnection() throws Exception {
        RunVariableContext context = RunVariableContext.builder("run-1")
                .environment(Map.of("shared", json.readTree("\"environment\"")))
                .caseVariables(Map.of("shared", json.readTree("\"case\"")))
                .dataRow(Map.of("shared", json.readTree("\"row\"")))
                .build();

        var bound = new JdbcSqlExecutor().bind("SELECT ${shared}", context);
        assertEquals("SELECT ?", bound.sql());
        assertEquals("row", bound.values().get(0).asText());
        assertThrows(RunVariableContext.VariableUndefinedException.class,
                () -> new JdbcSqlExecutor().bind("SELECT ${missing}", context));
    }
}
