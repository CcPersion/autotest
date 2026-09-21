package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;

record SuiteExecutionStep(String memberId, int position, String targetType, String targetId,
                          boolean enabled, JsonNode plan) {
    SuiteExecutionStep {
        if (memberId == null || memberId.isBlank()) throw new IllegalArgumentException("测试集合成员缺少 memberId");
        if (position < 0) throw new IllegalArgumentException("测试集合成员 position 不能为负数");
        targetType = targetType == null ? "" : targetType.toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("API_CASE", "SCENARIO").contains(targetType)) {
            throw new IllegalArgumentException("测试集合成员类型不支持: " + targetType);
        }
        if (targetId == null || targetId.isBlank()) throw new IllegalArgumentException("测试集合成员缺少 targetId");
        if (plan == null || !plan.isObject()) throw new IllegalArgumentException("测试集合成员缺少 plan");
    }
}
