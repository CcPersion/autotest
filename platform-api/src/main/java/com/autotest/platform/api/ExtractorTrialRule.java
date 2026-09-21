package com.autotest.platform.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

/** 即时试算请求中的一条提取规则。 */
public record ExtractorTrialRule(String type, String expression, String variable,
                                 JsonNode defaultValue, Boolean failIfMissing) {

    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }

    boolean effectiveFailIfMissing() {
        return failIfMissing == null || failIfMissing;
    }
}
