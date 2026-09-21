package com.autotest.platform.run;

import com.autotest.platform.environment.EnvironmentRecord;
import com.autotest.platform.environment.EnvironmentRepository;
import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class RunService {

    private static final Pattern SECRET_REFERENCE = Pattern.compile("\\$\\{secret:[A-Za-z0-9][A-Za-z0-9._-]*}");
    private static final String JMeter_VERSION = "5.6.3";
    private final RunRepository runs;
    private final ProjectRepository projects;
    private final EnvironmentRepository environments;
    private final ObjectMapper json;

    public RunService(RunRepository runs, ProjectRepository projects, EnvironmentRepository environments,
                      ObjectMapper json) {
        this.runs = runs;
        this.projects = projects;
        this.environments = environments;
        this.json = json;
    }

    @Transactional
    public CreateResult create(UUID projectId, UUID environmentId, String targetType, UUID targetId,
                               JsonNode executionPlan, String idempotencyKey, UUID actorId) {
        ProjectRecord project = projects.findById(projectId);
        if (project == null) {
            throw notFound();
        }
        if (project.archived()) {
            throw conflict("PROJECT_ARCHIVED", "项目已归档");
        }
        EnvironmentRecord environment = environments.findById(projectId, environmentId);
        if (environment == null) {
            throw notFound();
        }
        if (environment.archived()) {
            throw conflict("ENVIRONMENT_ARCHIVED", "环境已归档");
        }
        String normalizedKey = required(idempotencyKey, "幂等键不能为空");
        if (normalizedKey.length() > 256) {
            throw validation("幂等键过长");
        }
        String normalizedTargetType = required(targetType, "目标类型不能为空").toUpperCase(Locale.ROOT);
        if (!normalizedTargetType.equals("API_CASE") && !normalizedTargetType.equals("SCENARIO")
                && !normalizedTargetType.equals("TEST_SUITE")) {
            throw validation("目标类型不支持");
        }
        if (targetId == null || executionPlan == null || !executionPlan.isObject()) {
            throw validation("执行计划必须是 JSON 对象");
        }
        JsonNode effectivePlan = TargetPlanPolicy.attachAndValidate(executionPlan, project.targetAllowlist(), json);
        validatePlan(effectivePlan);
        validateVariables(effectivePlan);
        RunRecord existing = runs.findByIdempotencyKey(projectId, normalizedKey);
        if (existing != null) {
            return new CreateResult(existing, false);
        }
        try {
            String version = effectivePlan.path("jmeterVersion").asText(JMeter_VERSION);
            if (!JMeter_VERSION.equals(version)) {
                throw validation("JMeter 版本必须为 5.6.3");
            }
            return new CreateResult(runs.insert(projectId, environmentId, normalizedTargetType, targetId, actorId,
                    effectivePlan, normalizedKey, version), true);
        } catch (DuplicateKeyException exception) {
            return new CreateResult(runs.findByIdempotencyKey(projectId, normalizedKey), false);
        }
    }

    /** Server-built definition debug run. Formal /runs remains limited to saved executable targets. */
    @Transactional
    public CreateResult createDebug(UUID projectId, UUID environmentId, UUID definitionId,
                                    JsonNode executionPlan, String idempotencyKey, UUID actorId) {
        ProjectRecord project = projects.findById(projectId);
        if (project == null) throw notFound();
        if (project.archived()) throw conflict("PROJECT_ARCHIVED", "项目已归档");
        EnvironmentRecord environment = environments.findById(projectId, environmentId);
        if (environment == null) throw notFound();
        if (environment.archived()) throw conflict("ENVIRONMENT_ARCHIVED", "环境已归档");
        String normalizedKey = required(idempotencyKey, "幂等键不能为空");
        if (normalizedKey.length() > 256) throw validation("幂等键过长");
        if (executionPlan == null || !executionPlan.isObject()) throw validation("执行计划必须是 JSON 对象");
        validatePlan(executionPlan);
        validateVariables(executionPlan);
        RunRecord existing = runs.findByIdempotencyKey(projectId, normalizedKey);
        if (existing != null) return new CreateResult(existing, false);
        try {
            return new CreateResult(runs.insert(projectId, environmentId, "API_DEFINITION", definitionId, actorId,
                    executionPlan, normalizedKey, JMeter_VERSION), true);
        } catch (DuplicateKeyException exception) {
            return new CreateResult(runs.findByIdempotencyKey(projectId, normalizedKey), false);
        }
    }

    public RunRecord get(UUID projectId, UUID runId) {
        RunRecord run = runs.findById(projectId, runId);
        if (run == null) {
            throw notFound();
        }
        return run;
    }

    public List<RunRecord> list(UUID projectId, int limit) {
        if (projects.findById(projectId) == null) throw notFound();
        return runs.findRecentByProjectId(projectId, limit);
    }

    @Transactional
    public RunRecord cancel(UUID projectId, UUID runId) {
        RunRecord current = get(projectId, runId);
        if (!current.status().equals("PENDING") && !current.status().equals("RUNNING")) {
            throw conflict("RUN_NOT_CANCELABLE", "运行已结束，不能取消");
        }
        return runs.cancel(projectId, runId);
    }

    private static void validatePlan(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                boolean structuralContainer = (key.equals("cookies") || key.equals("secretrefs"))
                        && (field.getValue().isArray() || field.getValue().isObject());
                if (SetOfSensitive.KEYS.contains(key) && !structuralContainer
                        && !safeSecretValue(field.getValue())) {
                    throw validation("执行计划敏感字段只能使用密钥引用");
                }
                if (key.equals("headers") || key.equals("cookies")) {
                    validateNamedSecrets(field.getValue(), key.equals("cookies"));
                }
                validatePlan(field.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(RunService::validatePlan);
        }
    }

    private static void validateVariables(JsonNode node) {
        try {
            RunPlanVariablePreflight.validate(node);
        } catch (RunPlanVariablePreflight.ValidationException exception) {
            throw validation(exception.code() + ": " + exception.path());
        }
    }

    private static boolean safeSecretValue(JsonNode value) {
        return value.isTextual() && (SECRET_REFERENCE.matcher(value.textValue()).matches()
                || value.textValue().matches("(?:Bearer|Basic) \\$\\{secret:[A-Za-z0-9][A-Za-z0-9._-]*}"));
    }

    private static void validateNamedSecrets(JsonNode value, boolean everyCookieIsSecret) {
        if (!value.isArray()) {
            return;
        }
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw validation("执行计划 Header/Cookie 项必须是对象");
            }
            String name = item.path("name").asText("").toLowerCase(Locale.ROOT)
                    .replace("_", "").replace("-", "");
            if (everyCookieIsSecret || SetOfSensitive.KEYS.contains(name)) {
                if (!safeSecretValue(item.get("value"))) {
                    throw validation("执行计划敏感字段只能使用密钥引用");
                }
            }
        }
    }

    private static String required(String value, String message) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw validation(message);
        }
        return normalized;
    }

    private static ApiDomainException validation(String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", message);
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    public record CreateResult(RunRecord run, boolean created) {
    }

    private static final class SetOfSensitive {
        private static final java.util.Set<String> KEYS = java.util.Set.of(
                "password", "secret", "secretkey", "token", "accesstoken", "refreshtoken",
                "apikey", "authorization", "cookie", "cookies", "setcookie", "xapikey");
    }
}
