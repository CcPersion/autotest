package com.autotest.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JmeterAssertionTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsTheSharedAssertionContract() throws Exception {
        assertDoesNotThrow(() -> new JmeterAssertion("VARIABLE", "GREATER_THAN", "attempts",
                json.readTree("3")));
        assertDoesNotThrow(() -> new JmeterAssertion("JSON_PATH", "LESS_THAN", "$.count",
                json.readTree("10")));
        assertDoesNotThrow(() -> new JmeterAssertion("JMES_PATH", "CONTAINS", "tags",
                json.readTree("\"deprecated\"")));
        assertDoesNotThrow(() -> new JmeterAssertion("HEADER", "MATCHES", "X-Trace-Id",
                json.readTree("\"[a-z0-9]+\"")));
        assertDoesNotThrow(() -> new JmeterAssertion("COOKIE", "EXISTS", "sid", null));
    }

    @Test
    void mapsControlledVariableAndTargetedResponseAssertionsWithoutScripts() throws Exception {
        JmeterComponentMapper mapper = new JmeterComponentMapper();

        assertEquals("JmeterControlledAssertion", mapper.assertion(
                new JmeterAssertion("VARIABLE", "GREATER_THAN", "attempts", json.readTree("2")),
                "step-1").getClass().getSimpleName());
        assertEquals("JmeterControlledAssertion", mapper.assertion(
                new JmeterAssertion("JSON_PATH", "LESS_THAN", "$.count", json.readTree("10")),
                "step-1").getClass().getSimpleName());
        assertEquals("JmeterControlledAssertion", mapper.assertion(
                new JmeterAssertion("HEADER", "EQUALS", "X-Target", json.readTree("\"value-7\"")),
                "step-1").getClass().getSimpleName());
        assertEquals("JmeterControlledAssertion", mapper.assertion(
                new JmeterAssertion("COOKIE", "EXISTS", "sid", null), "step-1").getClass().getSimpleName());
    }

    @Test
    void acceptsLegacyRunnerHeaderCookieAssertionsWithoutTargetExpression() throws Exception {
        assertDoesNotThrow(() -> new JmeterAssertion("HEADER", "MATCHES", null,
                json.readTree("\"X-Trace-Id: [a-z0-9]+\"")));
        assertDoesNotThrow(() -> new JmeterAssertion("COOKIE", "NOT_CONTAINS", "",
                json.readTree("\"expired\"")));
    }

    @Test
    void acceptsLegacyRunnerExistenceExpectedFlagWithoutChangingTheJmeterContract() throws Exception {
        assertDoesNotThrow(() -> new JmeterAssertion("JSON_PATH", "EXISTS", "$.token",
                json.readTree("true")));
        assertDoesNotThrow(() -> new JmeterAssertion("JMES_PATH", "NOT_EXISTS", "token",
                json.readTree("false")));
    }

    @Test
    void rejectsInvalidTargetOperatorAndExpectedShapes() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new JmeterAssertion("HEADER", "EXISTS", "", null));
        assertThrows(IllegalArgumentException.class,
                () -> new JmeterAssertion("COOKIE", "MATCHES", "sid", json.readTree("1")));
        assertThrows(IllegalArgumentException.class,
                () -> new JmeterAssertion("JSON_PATH", "GREATER_THAN", "$.count", json.readTree("\"10\"")));
        assertThrows(IllegalArgumentException.class,
                () -> new JmeterAssertion("JSON_PATH", "NOT_CONTAINS", "$.tags", json.readTree("\"deprecated\"")));
        assertThrows(IllegalArgumentException.class,
                () -> new JmeterAssertion("JMES_PATH", "NOT_CONTAINS", "tags", json.readTree("\"deprecated\"")));
        assertThrows(IllegalArgumentException.class,
                () -> new JmeterAssertion("VARIABLE", "LESS_THAN", "attempts", json.readTree("true")));
        assertThrows(IllegalArgumentException.class,
                () -> new JmeterAssertion("BODY", "MATCHES", "", json.readTree("12")));
        assertThrows(IllegalArgumentException.class,
                () -> new JmeterAssertion("SCHEMA", "VALIDATE", "",
                        json.readTree("{\"$ref\":\"//evil.test/schema\"}")));
    }
}
