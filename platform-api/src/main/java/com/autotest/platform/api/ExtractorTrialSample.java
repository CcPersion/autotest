package com.autotest.platform.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** 试算使用的脱敏响应样本。 */
public record ExtractorTrialSample(Integer statusCode, Long durationMs, String body,
                                   Map<String, String> headers, Map<String, String> cookies) {

    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
