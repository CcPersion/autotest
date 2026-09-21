package com.autotest.platform.scenario;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScenarioResponse(UUID id, UUID projectId, String name, String description,
                               JsonNode variables, JsonNode settings, int revision, boolean archived,
                               Instant createdAt, Instant updatedAt, List<ScenarioStepResponse> steps) {
    public static ScenarioResponse from(ScenarioRecord scenario) {
        return new ScenarioResponse(scenario.id(), scenario.projectId(), scenario.name(), scenario.description(),
                scenario.variables(), scenario.settings(), scenario.revision(), scenario.archived(),
                scenario.createdAt(), scenario.updatedAt(), scenario.steps().stream().map(ScenarioStepResponse::from).toList());
    }
}
