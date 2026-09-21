package com.autotest.platform.scenario;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record ScenarioStepRecord(UUID id, UUID scenarioId, UUID parentId, int position, String kind,
                                 String title, boolean enabled, String section, String referenceMode,
                                 UUID apiCaseId, String failureStrategy, JsonNode stepConfig) {
}
