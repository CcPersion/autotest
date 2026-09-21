package com.autotest.platform.schedule;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record ScheduleWrite(String name, UUID suiteId, UUID environmentId, String cronExpression,
                            String zoneId, Boolean enabled, Integer revision) {
    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
