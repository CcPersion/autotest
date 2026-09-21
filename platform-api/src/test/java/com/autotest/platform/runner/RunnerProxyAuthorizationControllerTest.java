package com.autotest.platform.runner;

import com.autotest.platform.run.RunRecord;
import com.autotest.platform.run.RunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunnerProxyAuthorizationControllerTest {

    @Test
    void capabilityUsesPlatformResolvedAddressesInsteadOfRunnerSuppliedAddresses() throws Exception {
        UUID runId = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode plan = mapper.createObjectNode();
        plan.putObject("targetPolicySnapshot").set("rules", mapper.valueToTree(
                List.of("allowed.test:8080", "proxy.test:8081")));
        RunRepository runs = mock(RunRepository.class);
        when(runs.findById(runId)).thenReturn(new RunRecord(runId, UUID.randomUUID(), UUID.randomUUID(),
                "API_DEFINITION", UUID.randomUUID(), UUID.randomUUID(), "RUNNING", plan, "key", "5.6.3",
                Instant.now(), null, null, null, null, null, false, Instant.now()));
        RunnerProxyAuthorizationController controller = new RunnerProxyAuthorizationController(
                runs, mapper, "runner-secret", host -> new InetAddress[]{
                InetAddress.getByName(host.equals("allowed.test") ? "192.0.2.10" : "192.0.2.11")});

        var response = controller.authorize(runId, "runner-secret",
                new RunnerProxyAuthorizationController.AuthorizationRequest(
                        "http://allowed.test:8080/health", "http://proxy.test:8081",
                        List.of("127.0.0.1")));

        assertEquals(List.of("192.0.2.10"), response.getBody().addresses());
        assertEquals(List.of("192.0.2.11"), response.getBody().proxyAddresses());
        String payload = response.getBody().capability().split("\\.", 2)[0];
        ObjectNode claims = (ObjectNode) mapper.readTree(new String(
                Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
        assertTrue(claims.path("nonce").isTextual());
        assertFalse(claims.path("nonce").asText().isBlank());
        assertEquals("http://allowed.test:8080/health", claims.path("targetCanonical").asText());
        assertEquals("http://proxy.test:8081/", claims.path("proxyCanonical").asText());
    }

    @Test
    void refusesClientSuppliedPolicyWhenItIsNotInTheRunSnapshot() throws Exception {
        UUID runId = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode plan = mapper.createObjectNode();
        plan.putObject("targetPolicySnapshot").set("rules", mapper.valueToTree(List.of("allowed.test:8080")));
        RunRepository runs = mock(RunRepository.class);
        when(runs.findById(runId)).thenReturn(new RunRecord(runId, UUID.randomUUID(), UUID.randomUUID(),
                "API_DEFINITION", UUID.randomUUID(), UUID.randomUUID(), "RUNNING", plan, "key", "5.6.3",
                Instant.now(), null, null, null, null, null, false, Instant.now()));
        RunnerProxyAuthorizationController controller = new RunnerProxyAuthorizationController(
                runs, mapper, "runner-secret");

        assertThrows(RuntimeException.class, () -> controller.authorize(runId, "runner-secret",
                new RunnerProxyAuthorizationController.AuthorizationRequest(
                        "http://forbidden.test:8080/health", "http://proxy.test:8081",
                        List.of("203.0.113.10"))));
    }
}
