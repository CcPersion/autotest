package com.autotest.platform.scenario;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record ScenarioWrite(String name, String description, JsonNode variables, JsonNode settings,
                            List<ScenarioStepWrite> steps, Integer revision) {
    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
