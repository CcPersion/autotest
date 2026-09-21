package com.autotest.platform.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.UUID;

/** Runner 回传的单步骤报告事实；不包含密钥明文。 */
public record StepResultWrite(UUID stepId, String resultKey, int sequenceNo, String status, long durationMs,
                              JsonNode requestSummary, JsonNode responseSummary, JsonNode assertions,
                              JsonNode extractions, JsonNode errorSummary, Instant startedAt, Instant finishedAt) {

    public StepResultWrite(UUID stepId, String resultKey, int sequenceNo, String status, long durationMs,
                           JsonNode requestSummary, JsonNode responseSummary, JsonNode assertions,
                           JsonNode errorSummary, Instant startedAt, Instant finishedAt) {
        this(stepId, resultKey, sequenceNo, status, durationMs, requestSummary, responseSummary, assertions,
                JsonNodeFactory.instance.arrayNode(), errorSummary, startedAt, finishedAt);
    }

    public StepResultWrite {
        extractions = extractions == null ? JsonNodeFactory.instance.arrayNode() : extractions;
    }

    @JsonAnySetter
    public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
        throw new IllegalArgumentException("请求格式不正确");
    }
}
