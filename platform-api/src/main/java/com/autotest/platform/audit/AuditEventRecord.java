package com.autotest.platform.audit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record AuditEventRecord(UUID id, UUID actorId, UUID projectId, String action, String resourceType,
                               UUID resourceId, String traceId, UUID runId, UUID stepId,
                               JsonNode metadata, Instant createdAt) {
}
