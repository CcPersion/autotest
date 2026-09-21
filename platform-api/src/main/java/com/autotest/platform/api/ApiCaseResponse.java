package com.autotest.platform.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record ApiCaseResponse(UUID id, UUID projectId, UUID apiDefinitionId, String name,
                              JsonNode caseSpec, JsonNode variables, JsonNode assertions,
                              int revision, boolean archived, Instant createdAt, Instant updatedAt) {

    public static ApiCaseResponse from(ApiCaseRecord apiCase) {
        return new ApiCaseResponse(apiCase.id(), apiCase.projectId(), apiCase.apiDefinitionId(), apiCase.name(),
                apiCase.caseSpec(), apiCase.variables(), apiCase.assertions(), apiCase.revision(),
                apiCase.archived(), apiCase.createdAt(), apiCase.updatedAt());
    }
}
