package com.autotest.platform.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record ApiDefinitionWrite(UUID moduleId, String name, String method, String urlTemplate,
                                 JsonNode requestSpec, Integer revision) {

    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
