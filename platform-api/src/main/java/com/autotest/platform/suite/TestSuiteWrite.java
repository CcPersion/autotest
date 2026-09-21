package com.autotest.platform.suite;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

public record TestSuiteWrite(String name, String description, UUID environmentId,
                             List<MemberWrite> members, Integer revision) {
    public record MemberWrite(UUID id, String targetType, UUID targetId, int position, boolean enabled) {
        @JsonAnySetter
        public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
            throw new IllegalArgumentException("请求格式不正确");
        }
    }

    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
