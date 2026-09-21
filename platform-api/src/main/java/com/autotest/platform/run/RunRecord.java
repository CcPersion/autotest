package com.autotest.platform.run;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record RunRecord(
        UUID id,
        UUID projectId,
        UUID environmentId,
        String targetType,
        UUID targetId,
        UUID requestedBy,
        String status,
        JsonNode executionPlan,
        String idempotencyKey,
        String jmeterVersion,
        Instant startedAt,
        Instant finishedAt,
        Integer exitCode,
        String jmxPath,
        String jtlPath,
        String logPath,
        boolean cancelRequested,
        Instant createdAt
) {
}
