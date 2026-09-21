package com.autotest.platform.schedule;

import com.autotest.platform.run.RunRecord;
import com.autotest.platform.run.RunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunFinishedWebhookControllerTest {
    @Test
    void runnerCompletionPublishesOnlySafeSummary() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        RunRepository runs = mock(RunRepository.class);
        WebhookService webhooks = mock(WebhookService.class);
        RunRecord run = new RunRecord(runId, projectId, UUID.randomUUID(), "TEST_SUITE", UUID.randomUUID(), UUID.randomUUID(),
                "FAILED", new ObjectMapper().readTree("{\"password\":\"raw-password\"}"), "key", "5.6.3",
                Instant.now(), Instant.now(), 1, "/tmp/raw.jmx", "/tmp/raw.jtl", "/tmp/raw.log", false, Instant.now());
        when(runs.findById(runId)).thenReturn(run);

        new RunFinishedWebhookController(runs, webhooks, "runner-token")
                .finished(runId, "runner-token", new RunFinishedWebhookController.FinishedRequest("FAILED", 1, null, null));

        verify(webhooks).notify(eq(projectId), eq("RUN_FINISHED"), argThat(node ->
                node.path("runId").asText().equals(runId.toString())
                        && node.path("status").asText().equals("FAILED")
                        && !node.toString().contains("raw-password")
                        && !node.toString().contains("raw.jmx")));
    }
}
