package com.autotest.platform.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

public record ApiCaseWrite(String name, JsonNode caseSpec, JsonNode variables,
                           JsonNode assertions, Integer revision) {

    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
