package com.autotest.platform.scenario;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record ScenarioStepWrite(UUID id, UUID parentId, Integer position, String kind, String title,
                                Boolean enabled, String section, String referenceMode, UUID apiCaseId,
                                String failureStrategy, JsonNode stepConfig) {
    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
