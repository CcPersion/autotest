package com.autotest.platform.ai;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record AiPatchConfirmRequest(UUID previewId) {
    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
