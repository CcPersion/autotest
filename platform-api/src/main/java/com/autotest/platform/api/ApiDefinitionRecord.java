package com.autotest.platform.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record ApiDefinitionRecord(UUID id, UUID projectId, UUID moduleId, String name,
                                  String method, String urlTemplate, JsonNode requestSpec,
                                  int revision, boolean archived, Instant createdAt, Instant updatedAt) {
}
