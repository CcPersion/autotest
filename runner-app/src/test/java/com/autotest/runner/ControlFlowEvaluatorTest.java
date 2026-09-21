package com.autotest.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlFlowEvaluatorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void resolvesExtractedBeforeDataRowAndComparesNumbers() {
        ObjectNode plan = json.createObjectNode().put("left", "${code}").put("operator", "GREATER_THAN").put("right", "9");
        ObjectNode scopes = plan.putObject("variableScopes").putObject("dataRow").put("code", 1);
        Map<String, com.fasterxml.jackson.databind.JsonNode> extracted = new LinkedHashMap<>();
        extracted.put("code", json.getNodeFactory().numberNode(10));
        assertTrue(ControlFlowEvaluator.condition(plan, extracted));
        assertFalse(ControlFlowEvaluator.condition(plan, Map.of()));
    }

    @Test
    void expandsListVariableAndHonorsMaxIterations() throws Exception {
        ObjectNode plan = json.createObjectNode().put("items", "${ids}").put("itemVariable", "id").put("maxIterations", 2);
        plan.putObject("variableScopes").putObject("scenario").set("ids", json.readTree("[\"a\",\"b\",\"c\"]"));
        List<com.fasterxml.jackson.databind.JsonNode> values = ControlFlowEvaluator.listItems(plan, Map.of());
        assertEquals(3, values.size());
        assertEquals(2, ControlFlowEvaluator.maxIterations(plan));
    }

    @Test
    void resolvesControlFlowFromTheSameRunVariableContextAsOtherSteps() throws Exception {
        ObjectNode plan = json.createObjectNode().put("left", "${shared}").put("operator", "EQUALS").put("right", "row");
        plan.putObject("variableScopes").putObject("environment").put("shared", "environment");
        RunVariableContext context = RunVariableContext.builder("run-1")
                .environment(Map.of("shared", json.readTree("\"environment\"")))
                .dataRow(Map.of("shared", json.readTree("\"row\"")))
                .build();

        assertTrue(ControlFlowEvaluator.condition(plan, context));
        assertEquals("row", ControlFlowEvaluator.resolve(plan, "${shared}", context).asText());
    }
}
