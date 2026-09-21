package com.autotest.platform.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
/** 只包含用户提供的响应样本和提取器，不携带目标 URL 或密钥。 */
public record ExtractorTrialRequest(ExtractorTrialSample response,
                                    List<ExtractorTrialRule> extractors) {

    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
