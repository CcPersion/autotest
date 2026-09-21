package com.autotest.platform.environment;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record EnvironmentRecord(UUID id, UUID projectId, String name, String baseUrl,
                                JsonNode variables, JsonNode requestOptions, int revision, boolean archived,
                                Instant createdAt, Instant updatedAt) {
}
