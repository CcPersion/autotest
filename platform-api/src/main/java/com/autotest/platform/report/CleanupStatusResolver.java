package com.autotest.platform.report;

import com.autotest.platform.run.RunRecord;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 根据执行计划和已回传结果推导清理分区状态。 */
final class CleanupStatusResolver {
    private CleanupStatusResolver() {
    }

    static String resolve(RunRecord run, List<StepResultRecord> results) {
        if (run == null || !"SCENARIO".equals(run.targetType())) return "NOT_APPLICABLE";
        JsonNode steps = run.executionPlan() == null ? null : run.executionPlan().get("scenarioSteps");
        if (steps == null || !steps.isArray()) return "NOT_APPLICABLE";
        Set<UUID> cleanupIds = new HashSet<>();
        for (JsonNode step : steps) {
            if (!"CLEANUP".equalsIgnoreCase(step.path("section").asText("MAIN"))) continue;
            try {
                cleanupIds.add(UUID.fromString(step.path("stepId").asText()));
            } catch (IllegalArgumentException ignored) {
                // 非 UUID 临时计划不会被误判成已清理。
            }
        }
        if (cleanupIds.isEmpty()) return "NOT_APPLICABLE";
        Set<UUID> observed = new HashSet<>();
        boolean failed = false;
        for (StepResultRecord result : results == null ? List.<StepResultRecord>of() : results) {
            if (!cleanupIds.contains(result.stepId())) continue;
            observed.add(result.stepId());
            failed |= "FAILED".equals(result.status()) || "INTERRUPTED".equals(result.status());
        }
        if (failed) return "FAILED";
        if (observed.containsAll(cleanupIds)) return "PASSED";
        if ("PENDING".equals(run.status()) || "RUNNING".equals(run.status())) return "PENDING";
        return "NOT_EXECUTED";
    }
}
