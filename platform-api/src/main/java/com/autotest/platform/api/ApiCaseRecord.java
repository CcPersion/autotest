package com.autotest.platform.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record ApiCaseRecord(UUID id, UUID projectId, UUID apiDefinitionId, String name,
                            JsonNode caseSpec, JsonNode variables, JsonNode assertions,
                            int revision, boolean archived, Instant createdAt, Instant updatedAt) {
}
