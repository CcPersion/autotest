package com.autotest.platform.ai;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

public record AiModelConfigWrite(String name, String baseUrl, String modelName, String apiKeySecretRef,
                                 Boolean enabled, Integer revision) {
    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
