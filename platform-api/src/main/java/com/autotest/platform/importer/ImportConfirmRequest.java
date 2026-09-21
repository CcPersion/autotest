package com.autotest.platform.importer;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.List;
import java.util.UUID;

public record ImportConfirmRequest(UUID previewId, List<ImportChoice> choices) {
    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, Object ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
