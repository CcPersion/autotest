package com.autotest.contracts.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AssertionRuleTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void validatesOperatorAndExpectedByAssertionType() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionRule(0, "STATUS", "CONTAINS", "", json.readTree("200")));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionRule(0, "STATUS", "EQUALS", "", json.readTree("99")));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionRule(0, "HEADER", "EXISTS", "X-Trace", json.readTree("true")));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionRule(0, "VARIABLE", "EQUALS", "token", null));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionRule(0, "SCHEMA", "VALIDATE", "",
                        json.readTree("{\"$ref\":\"https://evil.test/schema\"}")));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionRule(0, "SCHEMA", "VALIDATE", "",
                        json.readTree("{\"$ref\":\"//evil.test/schema\"}")));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionRule(0, "SCHEMA", "VALIDATE", "",
                        json.readTree("{\"properties\":{\"x\":{\"$ref\":\"scheme://evil.test/schema\"}}}")));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionRule(0, "JSON_PATH", "GREATER_THAN", "$.count", json.readTree("\"5\"")));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionRule(0, "JMES_PATH", "LESS_THAN", "count", json.readTree("true")));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionRule(0, "VARIABLE", "GREATER_THAN", "count", json.readTree("\"5\"")));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionRule(0, "BODY", "MATCHES", "", json.readTree("5")));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionRule(0, "HEADER", "MATCHES", "X-Trace", json.readTree("5")));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionRule(0, "COOKIE", "MATCHES", "sid", json.readTree("false")));
        assertThrows(IllegalArgumentException.class,
                () -> new AssertionRule(0, "BODY", "MATCHES", "",
                        com.fasterxml.jackson.databind.node.TextNode.valueOf("(a|aa)+$")));
    }
}
