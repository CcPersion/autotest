package com.autotest.platform.scenario;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record ScenarioStepResponse(UUID id, UUID parentId, int position, String kind, String title,
                                   boolean enabled, String section, String referenceMode, UUID apiCaseId,
                                   String failureStrategy, JsonNode stepConfig) {
    static ScenarioStepResponse from(ScenarioStepRecord step) {
        return new ScenarioStepResponse(step.id(), step.parentId(), step.position(), step.kind(), step.title(),
                step.enabled(), step.section(), step.referenceMode(), step.apiCaseId(), step.failureStrategy(), step.stepConfig());
    }
}
