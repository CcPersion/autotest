package com.autotest.platform.report;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record StepResultRecord(UUID id, UUID runId, UUID stepId, String resultKey, int sequenceNo, String status,
                               long durationMs, JsonNode requestSummary, JsonNode responseSummary,
                               JsonNode assertions, JsonNode extractions, JsonNode errorSummary, Instant startedAt,
                               Instant finishedAt, Instant createdAt) {
}
