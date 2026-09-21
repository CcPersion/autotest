package com.autotest.platform.scenario;

import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ScenarioValidator {
    private static final Set<String> KINDS = Set.of("API_CASE", "HTTP", "SQL", "REDIS", "CONDITION", "LOOP", "WAIT", "CLEANUP");
    private static final Set<String> SECTIONS = Set.of("MAIN", "CLEANUP");
    private static final Set<String> REFERENCE_MODES = Set.of("REFERENCE", "COPY");
    private static final Set<String> FAILURE_STRATEGIES = Set.of("STOP", "CONTINUE", "RETRY");
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private ScenarioValidator() {
    }

    static String name(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw invalid("name", "INVALID_NAME", "场景名称长度必须为 1 到 256 个字符");
        }
        return normalized;
    }

    static void validate(ScenarioWrite input) {
        if (input == null) throw invalid("scenario", "INVALID_JSON", "场景请求不能为空");
        object(input.variables(), "variables", "场景变量必须是 JSON 对象");
        object(input.settings(), "settings", "场景设置必须是 JSON 对象");
        if (input.steps() == null || input.steps().isEmpty()) {
            throw invalid("steps", "INVALID_STEPS", "场景至少需要一个步骤");
        }
        if (input.steps().size() > 200) {
            throw invalid("steps", "INVALID_STEPS", "场景最多支持 200 个步骤");
        }
        Map<UUID, ScenarioStepWrite> byId = new HashMap<>();
        Set<String> siblingPositions = new HashSet<>();
        for (int index = 0; index < input.steps().size(); index++) {
            ScenarioStepWrite step = input.steps().get(index);
            String path = "steps[" + index + "]";
            if (step == null || step.id() == null) throw invalid(path + ".id", "INVALID_STEP_ID", "步骤 id 不能为空");
            if (byId.put(step.id(), step) != null) throw invalid(path + ".id", "DUPLICATE_STEP_ID", "步骤 id 不能重复");
            if (step.position() == null || step.position() < 0) throw invalid(path + ".position", "INVALID_POSITION", "步骤位置必须是非负整数");
            String kind = upper(step.kind());
            if (!KINDS.contains(kind)) throw invalid(path + ".kind", "INVALID_STEP_KIND", "步骤类型不支持");
            requiredText(step.title(), path + ".title", "步骤名称不能为空");
            String section = step.section() == null ? "MAIN" : upper(step.section());
            if (!SECTIONS.contains(section)) throw invalid(path + ".section", "INVALID_SECTION", "步骤分区不支持");
            String strategy = step.failureStrategy() == null ? "STOP" : upper(step.failureStrategy());
            if (!FAILURE_STRATEGIES.contains(strategy)) throw invalid(path + ".failureStrategy", "INVALID_FAILURE_STRATEGY", "失败策略不支持");
            if (step.stepConfig() == null || !step.stepConfig().isObject()) throw invalid(path + ".stepConfig", "INVALID_JSON", "步骤配置必须是 JSON 对象");
            if ("RETRY".equals(strategy)) validateRetry(step.stepConfig(), path + ".stepConfig");
            if ("HTTP".equals(kind)) validateHttpPlan(step.stepConfig(), path + ".stepConfig");
            if ("SQL".equals(kind)) SqlStepValidator.validate(step.stepConfig(), path + ".stepConfig");
            if ("REDIS".equals(kind)) RedisStepValidator.validate(step.stepConfig(), path + ".stepConfig");
            if ("CONDITION".equals(kind)) ControlFlowStepValidator.validateCondition(step.stepConfig(), path + ".stepConfig");
            if ("LOOP".equals(kind)) ControlFlowStepValidator.validateLoop(step.stepConfig(), path + ".stepConfig");
            String mode = step.referenceMode() == null ? ("API_CASE".equals(kind) ? "REFERENCE" : null) : upper(step.referenceMode());
            if (mode != null && !REFERENCE_MODES.contains(mode)) throw invalid(path + ".referenceMode", "INVALID_REFERENCE_MODE", "复用方式不支持");
            if ("API_CASE".equals(kind) && step.apiCaseId() == null) throw invalid(path + ".apiCaseId", "INVALID_REFERENCE", "引用接口用例步骤必须选择接口用例");
            if (!"API_CASE".equals(kind) && step.apiCaseId() != null) throw invalid(path + ".apiCaseId", "INVALID_REFERENCE", "非接口用例步骤不能引用接口用例");
            if (!"API_CASE".equals(kind) && mode != null) throw invalid(path + ".referenceMode", "INVALID_REFERENCE_MODE", "非接口用例步骤不能设置复用方式");
            if ("CLEANUP".equals(kind) && !"CLEANUP".equals(section)) throw invalid(path + ".section", "INVALID_SECTION", "清理步骤必须位于清理分区");
            String siblingKey = String.valueOf(step.parentId()) + ":" + step.position();
            if (!siblingPositions.add(siblingKey)) throw invalid(path + ".position", "DUPLICATE_POSITION", "同级步骤位置不能重复");
        }
        for (Map.Entry<UUID, ScenarioStepWrite> entry : byId.entrySet()) {
            UUID parentId = entry.getValue().parentId();
            if (parentId != null && !byId.containsKey(parentId)) throw invalid("steps." + entry.getKey(), "INVALID_PARENT", "父步骤不存在");
            if (parentId != null) {
                ScenarioStepWrite parent = byId.get(parentId);
                String parentKind = upper(parent.kind());
                if ("CONDITION".equals(parentKind)) {
                    String branch = upper(entry.getValue().stepConfig().path("branch").asText("THEN"));
                    if (!Set.of("THEN", "ELSE").contains(branch)) {
                        throw invalid("steps." + entry.getKey() + ".stepConfig.branch", "INVALID_BRANCH", "条件子步骤只能属于 THEN 或 ELSE 分支");
                    }
                }
                if ("LOOP".equals(parentKind) && entry.getValue().stepConfig().has("branch")) {
                    String branch = upper(entry.getValue().stepConfig().path("branch").asText(""));
                    if (!branch.isBlank() && !"BODY".equals(branch)) {
                        throw invalid("steps." + entry.getKey() + ".stepConfig.branch", "INVALID_BRANCH", "循环子步骤 branch 只能为 BODY");
                    }
                }
            }
            Set<UUID> seen = new HashSet<>();
            UUID cursor = parentId;
            while (cursor != null) {
                if (!seen.add(cursor)) throw invalid("steps." + entry.getKey(), "CYCLE", "步骤不能形成循环引用");
                ScenarioStepWrite parent = byId.get(cursor);
                cursor = parent == null ? null : parent.parentId();
                if (seen.size() > 10) throw invalid("steps." + entry.getKey(), "MAX_DEPTH", "步骤嵌套最多 10 层");
            }
        }
    }

    private static void object(JsonNode node, String path, String message) {
        if (node == null || !node.isObject()) throw invalid(path, "INVALID_JSON", message);
    }

    private static String requiredText(String value, String path, String message) {
        if (value == null || value.isBlank() || value.length() > 256) throw invalid(path, "INVALID_TEXT", message);
        return value.strip();
    }

    private static String upper(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static void validateHttpPlan(JsonNode stepConfig, String path) {
        JsonNode plan = stepConfig.get("plan");
        if (plan == null || !plan.isObject()) {
            throw invalid(path + ".plan", "INVALID_HTTP_PLAN", "自定义 HTTP 步骤必须提供 plan 对象");
        }
        String method = upper(plan.path("method").asText(null));
        if (!HTTP_METHODS.contains(method)) {
            throw invalid(path + ".plan.method", "INVALID_HTTP_PLAN", "自定义 HTTP method 不支持");
        }
        String urlTemplate = plan.path("urlTemplate").asText("").strip();
        if (urlTemplate.isEmpty() || urlTemplate.length() > 2048) {
            throw invalid(path + ".plan.urlTemplate", "INVALID_HTTP_PLAN", "自定义 HTTP urlTemplate 不能为空且不能超过 2048 个字符");
        }
    }

    private static void validateRetry(JsonNode stepConfig, String path) {
        JsonNode retry = stepConfig.get("retry");
        if (retry == null || !retry.isObject()) throw invalid(path + ".retry", "INVALID_RETRY", "重试策略必须提供 retry 对象");
        int maxAttempts = retry.path("maxAttempts").asInt(-1);
        long intervalMillis = retry.path("intervalMillis").asLong(-1);
        if (maxAttempts < 2 || maxAttempts > 5) throw invalid(path + ".retry.maxAttempts", "INVALID_RETRY", "重试次数必须在 2 到 5 次之间");
        if (intervalMillis < 0 || intervalMillis > 60_000L) throw invalid(path + ".retry.intervalMillis", "INVALID_RETRY", "重试间隔必须在 0 到 60 秒之间");
        JsonNode retryOn = retry.get("retryOn");
        if (retryOn != null && (!retryOn.isArray() || retryOn.size() > 8)) throw invalid(path + ".retry.retryOn", "INVALID_RETRY", "retryOn 必须是最多 8 项的数组");
    }

    private static ApiDomainException invalid(String field, String code, String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), code, message, Map.of("field", field));
    }
}
