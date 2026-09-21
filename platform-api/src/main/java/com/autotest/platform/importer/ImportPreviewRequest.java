package com.autotest.platform.importer;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.UUID;

public record ImportPreviewRequest(UUID moduleId, String source) {
    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, Object ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
