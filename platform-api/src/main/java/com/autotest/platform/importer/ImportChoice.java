package com.autotest.platform.importer;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public record ImportChoice(int index, ImportAction action) {
    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, Object ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
