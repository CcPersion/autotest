package com.autotest.platform.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisStepValidatorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void allowsReadCommandsAndRejectsUnknownCommand() {
        ObjectNode config = json.createObjectNode().put("dataSourceId", "source").put("command", "GET").put("key", "token");
        assertDoesNotThrow(() -> RedisStepValidator.validate(config, "stepConfig"));
        config.put("command", "EVAL");
        assertThrows(RuntimeException.class, () -> RedisStepValidator.validate(config, "stepConfig"));
    }

    @Test
    void writeCommandsRequireExplicitConfirmation() {
        ObjectNode config = json.createObjectNode().put("dataSourceId", "source").put("command", "SET").put("key", "token").put("value", "v");
        assertThrows(RuntimeException.class, () -> RedisStepValidator.validate(config, "stepConfig"));
        config.put("allowWrite", true).put("confirmed", true);
        assertDoesNotThrow(() -> RedisStepValidator.validate(config, "stepConfig"));
    }
}
