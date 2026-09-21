package com.autotest.platform.schedule;

import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.secret.SecretRecord;
import com.autotest.platform.secret.SecretRepository;
import com.autotest.platform.secret.SecretService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookServiceTest {
    @Test
    void retriesSignedSanitizedSummaryWithoutSendingSensitiveEvidence() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        Instant now = Instant.now();
        WebhookRepository repository = mock(WebhookRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        SecretRepository secrets = mock(SecretRepository.class);
        SecretService secretService = mock(SecretService.class);
        WebhookRecord webhook = new WebhookRecord(webhookId, projectId, "ci", "http://127.0.0.1/hook", "hook-secret",
                List.of("RUN_FINISHED"), true, 0, now, now);
        when(repository.findEnabled(projectId, "RUN_FINISHED")).thenReturn(List.of(webhook));
        when(secretService.resolveForRunner(projectId, "hook-secret")).thenReturn("signing-secret");
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        WebhookTransport transport = (url, headers, payload) -> {
            body.set(payload);
            return attempts.incrementAndGet() == 1 ? 503 : 204;
        };

        int delivered = new WebhookService(repository, projects, secrets, secretService, new ObjectMapper(), transport)
                .notify(projectId, "RUN_FINISHED", new ObjectMapper().readTree(
                        "{\"runId\":\"run-1\",\"status\":\"FAILED\",\"password\":\"raw-password\","
                                + "\"response\":{\"body\":\"raw-body\"},\"log\":\"raw-log\"}"));

        assertEquals(1, delivered);
        assertEquals(2, attempts.get());
        assertFalse(body.get().contains("raw-password"));
        assertFalse(body.get().contains("raw-body"));
        assertFalse(body.get().contains("raw-log"));
    }
}
