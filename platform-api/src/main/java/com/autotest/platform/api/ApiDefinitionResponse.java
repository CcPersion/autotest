package com.autotest.platform.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record ApiDefinitionResponse(UUID id, UUID projectId, UUID moduleId, String name,
                                    String method, String urlTemplate, JsonNode requestSpec,
                                    int revision, boolean archived, Instant createdAt, Instant updatedAt) {

    public static ApiDefinitionResponse from(ApiDefinitionRecord definition) {
        return new ApiDefinitionResponse(definition.id(), definition.projectId(), definition.moduleId(),
                definition.name(), definition.method(), definition.urlTemplate(), definition.requestSpec(),
                definition.revision(), definition.archived(), definition.createdAt(), definition.updatedAt());
    }
}
