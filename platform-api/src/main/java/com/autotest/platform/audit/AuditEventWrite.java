package com.autotest.platform.audit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record AuditEventWrite(UUID actorId, UUID projectId, String action, String resourceType,
                              UUID resourceId, String traceId, UUID runId, UUID stepId,
                              JsonNode metadata) {
}
