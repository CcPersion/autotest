package com.autotest.platform.ai;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

public record AiPatchWrite(String title, String targetType, UUID targetId, UUID parentId,
                           Integer baseRevision, List<AiPatchOperation> operations) {
    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
