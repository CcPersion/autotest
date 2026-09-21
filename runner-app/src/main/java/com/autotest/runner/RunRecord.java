package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Runner 从 runs 表读取的最小运行事实。 */
public record RunRecord(
        UUID id,
        String status,
        String jmeterVersion,
        Instant startedAt,
        Instant finishedAt,
        boolean cancelRequested,
        Integer exitCode,
        String jmxPath,
        String jtlPath,
        String logPath,
        JsonNode executionPlan
) {
}
