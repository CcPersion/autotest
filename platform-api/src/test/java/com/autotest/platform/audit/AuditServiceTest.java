package com.autotest.platform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditServiceTest {

    private final AuditRepository repository = mock(AuditRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);
    private final AuditService service = new AuditService(repository, new ObjectMapper(), clock);

    @Test
    void masksSensitiveMetadataBeforePersisting() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(repository.insert(any())).thenAnswer(invocation -> {
            AuditEventWrite write = invocation.getArgument(0);
            return new AuditEventRecord(UUID.randomUUID(), write.actorId(), write.projectId(), write.action(),
                    write.resourceType(), write.resourceId(), write.traceId(), write.runId(), write.stepId(),
                    write.metadata(), clock.instant());
        });

        AuditEventRecord event = service.record(null, projectId, "RUN_CREATED", "RUN", runId,
                "trace-1", runId, null,
                new ObjectMapper().readTree("{\"token\":\"plain-token\",\"message\":\"ok\",\"nested\":{\"password\":\"plain-password\"}}"));

        assertEquals("***", event.metadata().path("token").asText());
        assertEquals("***", event.metadata().path("nested").path("password").asText());
        assertEquals("ok", event.metadata().path("message").asText());
        assertFalse(event.metadata().toString().contains("plain-token"));
        assertFalse(event.metadata().toString().contains("plain-password"));
    }
}
