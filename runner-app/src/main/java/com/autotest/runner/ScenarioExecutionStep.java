package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;

/** 场景执行计划中的首批受控步骤；不承载用户脚本或任意 JMeter 组件。 */
record ScenarioExecutionStep(String stepId, String kind, boolean enabled, String section,
                             String failureStrategy, JsonNode plan, long waitMillis,
                             String parentId, int position, String branch,
                             int maxAttempts, long retryIntervalMillis) {

    ScenarioExecutionStep {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("场景步骤缺少 stepId");
        }
        kind = kind == null ? "" : kind.toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("API_CASE", "HTTP", "SQL", "REDIS", "CONDITION", "LOOP", "WAIT").contains(kind)) {
            throw new IllegalArgumentException("当前 Runner 不支持场景步骤: " + kind);
        }
        if (position < 0) throw new IllegalArgumentException("场景步骤 position 不能为负数");
        section = section == null || section.isBlank() ? "MAIN" : section.toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("MAIN", "CLEANUP").contains(section)) {
            throw new IllegalArgumentException("场景步骤 section 不合法");
        }
        failureStrategy = failureStrategy == null || failureStrategy.isBlank()
                ? (section.equals("CLEANUP") ? "CONTINUE" : "STOP")
                : failureStrategy.toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("STOP", "CONTINUE", "RETRY").contains(failureStrategy)) {
            throw new IllegalArgumentException("当前 Runner 只支持 STOP/CONTINUE/RETRY 失败策略");
        }
        if ("RETRY".equals(failureStrategy)) {
            if (maxAttempts < 2 || maxAttempts > 5) throw new IllegalArgumentException("重试次数必须在 2 到 5 次之间");
            if (retryIntervalMillis < 0 || retryIntervalMillis > 60_000L) throw new IllegalArgumentException("重试间隔必须在 0 到 60 秒之间");
        } else {
            maxAttempts = 1;
            retryIntervalMillis = 0;
        }
        if (kind.equals("WAIT")) {
            if (waitMillis < 0 || waitMillis > 86_400_000L) {
                throw new IllegalArgumentException("等待时间必须在 0 到 24 小时之间");
            }
            plan = null;
        } else {
            if (plan == null || !plan.isObject()) {
                throw new IllegalArgumentException("场景步骤缺少执行计划");
            }
            waitMillis = 0;
        }
        branch = branch == null || branch.isBlank() ? (kind.equals("LOOP") ? "BODY" : "THEN")
                : branch.toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("THEN", "ELSE", "BODY").contains(branch)) {
            throw new IllegalArgumentException("场景步骤 branch 不合法");
        }
    }
}
