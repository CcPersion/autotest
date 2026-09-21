package com.autotest.platform.schedule;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record WebhookWrite(String name, String url, String secretRef, List<String> events,
                           Boolean enabled, Integer revision) {
    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
