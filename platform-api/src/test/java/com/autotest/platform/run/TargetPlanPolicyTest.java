package com.autotest.platform.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TargetPlanPolicyTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void attachesNormalizedRulesWithoutMutatingInput() throws Exception {
        var input = json.readTree("""
                {"planId":"p","baseUrl":"https://API.Example.test","method":"GET",
                 "urlTemplate":"/health","body":{"type":"NONE"}}
                """);

        var output = TargetPlanPolicy.attachAndValidate(input, List.of("API.Example.test."), json);

        assertEquals("api.example.test", output.path("targetAllowlist").get(0).asText());
        assertEquals("api.example.test", output.path("targetPolicySnapshot").path("rules").get(0).asText());
        assertEquals(true, output.path("targetPolicyRequired").asBoolean());
        assertEquals(0, input.path("targetAllowlist").size());
    }

    @Test
    void rejectsAbsoluteUrlOutsideProjectRules() throws Exception {
        var input = json.readTree("""
                {"planId":"p","baseUrl":"https://api.example.test","method":"GET",
                 "urlTemplate":"https://evil.example.test/health","body":{"type":"NONE"}}
                """);

        assertThrows(RuntimeException.class,
                () -> TargetPlanPolicy.attachAndValidate(input, List.of("api.example.test"), json));
    }

    @Test
    void acceptsExplicitPortIpInsideCidrRule() throws Exception {
        var input = json.readTree("""
                {"planId":"p","baseUrl":"http://172.31.0.10:8080",
                 "method":"GET","urlTemplate":"/health","body":{"type":"NONE"}}
                """);

        var output = TargetPlanPolicy.attachAndValidate(input, List.of("172.31.0.0/24:8080"), json);

        assertEquals(true, output.path("targetPolicyRequired").asBoolean());
        assertEquals("172.31.0.0/24:8080", output.path("targetPolicySnapshot").path("rules").get(0).asText());
    }
}
