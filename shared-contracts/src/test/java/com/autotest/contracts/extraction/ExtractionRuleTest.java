package com.autotest.contracts.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtractionRuleTest {

    @Test
    void rejectsUnknownTypeAndInvalidVariable() {
        ObjectMapper json = new ObjectMapper();
        assertThrows(IllegalArgumentException.class,
                () -> new ExtractionRule("SCRIPT", "$.value", "value", null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new ExtractionRule("JSON_PATH", "$.value", "secret:token", json.nullNode(), true));
    }
}
