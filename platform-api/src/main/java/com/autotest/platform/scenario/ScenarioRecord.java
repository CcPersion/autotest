package com.autotest.platform.scenario;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScenarioRecord(UUID id, UUID projectId, String name, String description,
                             JsonNode variables, JsonNode settings, int revision, boolean archived,
                             Instant createdAt, Instant updatedAt, List<ScenarioStepRecord> steps) {
}
