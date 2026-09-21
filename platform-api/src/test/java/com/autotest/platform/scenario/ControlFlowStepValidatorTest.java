package com.autotest.platform.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlFlowStepValidatorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void validatesConditionOperatorsAndScalarValues() {
        ObjectNode config = json.createObjectNode()
                .put("left", "${status}").put("operator", "EQUALS").put("right", "READY");
        assertDoesNotThrow(() -> ControlFlowStepValidator.validateCondition(config, "stepConfig"));
        config.put("operator", "EVAL");
        assertThrows(RuntimeException.class, () -> ControlFlowStepValidator.validateCondition(config, "stepConfig"));
        config.put("operator", "EQUALS").put("left", "${__javaScript(1+1)}");
        assertThrows(RuntimeException.class, () -> ControlFlowStepValidator.validateCondition(config, "stepConfig"));
    }

    @Test
    void validatesFixedListAndWhileLoopLimits() {
        ObjectNode fixed = json.createObjectNode().put("mode", "FIXED").put("count", 3).put("maxIterations", 5);
        assertDoesNotThrow(() -> ControlFlowStepValidator.validateLoop(fixed, "stepConfig"));
        fixed.put("count", 6);
        assertThrows(RuntimeException.class, () -> ControlFlowStepValidator.validateLoop(fixed, "stepConfig"));

        ObjectNode list = json.createObjectNode().put("mode", "LIST").put("items", "${ids}").put("itemVariable", "id");
        assertDoesNotThrow(() -> ControlFlowStepValidator.validateLoop(list, "stepConfig"));
        ObjectNode whileConfig = json.createObjectNode().put("mode", "WHILE").put("left", "${ready}")
                .put("operator", "EQUALS").put("right", "true");
        assertDoesNotThrow(() -> ControlFlowStepValidator.validateLoop(whileConfig, "stepConfig"));
        whileConfig.put("maxIterations", 1001);
        assertThrows(RuntimeException.class, () -> ControlFlowStepValidator.validateLoop(whileConfig, "stepConfig"));
    }
}
