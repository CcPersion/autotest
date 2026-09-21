package com.autotest.platform.report;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 从不可变集合运行计划中提取报告需要的成员元数据，不重新读取可变资产。 */
public final class SuiteMemberReportResolver {
    private SuiteMemberReportResolver() {
    }

    public static List<Member> resolve(JsonNode executionPlan) {
        if (executionPlan == null || !"TEST_SUITE".equals(executionPlan.path("targetType").asText())) {
            return List.of();
        }
        JsonNode steps = executionPlan.path("suiteSteps");
        if (!steps.isArray()) return List.of();
        List<Member> members = new ArrayList<>();
        for (JsonNode step : steps) {
            if (step == null || !step.isObject()) continue;
            String memberId = step.path("memberId").asText("");
            String targetType = step.path("targetType").asText("");
            String targetId = step.path("targetId").asText("");
            if (memberId.isBlank() || targetType.isBlank() || targetId.isBlank()) continue;
            members.add(new Member(memberId, step.path("position").asInt(Integer.MAX_VALUE), targetType, targetId,
                    step.path("targetName").asText(targetId), step.path("enabled").asBoolean(true)));
        }
        members.sort(Comparator.comparingInt(Member::position).thenComparing(Member::memberId));
        return List.copyOf(members);
    }

    /** 从集合运行结果键中恢复成员范围；普通单接口/场景结果返回 null。 */
    public static String memberIdForResultKey(String resultKey, List<Member> members) {
        if (resultKey == null || resultKey.isBlank() || members == null) return null;
        for (Member member : members) {
            String prefix = member.memberId();
            if (resultKey.equals(prefix) || resultKey.startsWith(prefix + "/") || resultKey.startsWith(prefix + "#")) {
                return prefix;
            }
        }
        return null;
    }

    public record Member(String memberId, int position, String targetType, String targetId, String targetName, boolean enabled) {
    }
}
