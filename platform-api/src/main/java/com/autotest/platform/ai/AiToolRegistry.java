package com.autotest.platform.ai;

import com.autotest.platform.api.ApiCaseRecord;
import com.autotest.platform.api.ApiCaseRepository;
import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.report.ReportService;
import com.autotest.platform.report.RunReport;
import com.autotest.platform.report.StepResultRecord;
import com.autotest.platform.scenario.ScenarioRecord;
import com.autotest.platform.scenario.ScenarioRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** AI 只能通过这里暴露的结构化领域工具访问平台事实。 */
@Component
public class AiToolRegistry {
    private final ObjectMapper mapper;
    private final ProjectRepository projects;
    private final ApiCaseRepository apiCases;
    private final ScenarioRepository scenarios;
    private final ReportService reports;

    public AiToolRegistry(ObjectMapper mapper, ProjectRepository projects, ApiCaseRepository apiCases,
                          ScenarioRepository scenarios, ReportService reports) {
        this.mapper = mapper;
        this.projects = projects;
        this.apiCases = apiCases;
        this.scenarios = scenarios;
        this.reports = reports;
    }

    public List<AiToolDefinition> definitions() {
        return List.of(
                new AiToolDefinition("list_projects", "列出当前用户可见的未归档项目", schema()),
                new AiToolDefinition("list_api_cases", "列出指定项目的接口用例元数据，不返回请求密钥", schema()),
                new AiToolDefinition("list_scenarios", "列出指定项目的场景元数据", schema()),
                new AiToolDefinition("get_run_report", "读取指定运行的脱敏状态和步骤摘要", schema()),
                new AiToolDefinition("create_draft_patch", "生成待人工确认的结构化草稿 Patch，不写入资产", patchSchema()));
    }

    public JsonNode invoke(String name, JsonNode arguments) {
        JsonNode args = arguments == null || arguments.isNull() ? mapper.createObjectNode() : arguments;
        return switch (name) {
            case "list_projects" -> listProjects();
            case "list_api_cases" -> listApiCases(requiredUuid(args, "projectId"));
            case "list_scenarios" -> listScenarios(requiredUuid(args, "projectId"));
            case "get_run_report" -> getRunReport(requiredUuid(args, "projectId"), requiredUuid(args, "runId"));
            case "create_draft_patch" -> createDraftPatch(args);
            default -> throw new IllegalArgumentException("AI 工具不存在");
        };
    }

    private JsonNode listProjects() {
        ensure(projects);
        ArrayNode result = mapper.createArrayNode();
        for (ProjectRecord project : projects.findAll(false)) {
            result.addObject().put("id", project.id().toString())
                    .put("name", project.name())
                    .put("description", project.description() == null ? "" : project.description())
                    .put("revision", project.revision());
        }
        return result;
    }

    private JsonNode listApiCases(UUID projectId) {
        ensure(apiCases);
        ArrayNode result = mapper.createArrayNode();
        for (ApiCaseRecord apiCase : apiCases.findActiveByProjectId(projectId)) {
            result.addObject().put("id", apiCase.id().toString())
                    .put("definitionId", apiCase.apiDefinitionId().toString())
                    .put("name", apiCase.name())
                    .put("revision", apiCase.revision());
        }
        return result;
    }

    private JsonNode listScenarios(UUID projectId) {
        ensure(scenarios);
        ArrayNode result = mapper.createArrayNode();
        for (ScenarioRecord scenario : scenarios.findAll(projectId, false)) {
            result.addObject().put("id", scenario.id().toString())
                    .put("name", scenario.name())
                    .put("description", scenario.description() == null ? "" : scenario.description())
                    .put("revision", scenario.revision());
        }
        return result;
    }

    private JsonNode getRunReport(UUID projectId, UUID runId) {
        ensure(reports);
        RunReport report = reports.get(projectId, runId);
        ObjectNode result = mapper.createObjectNode().put("runId", runId.toString())
                .put("status", report.run().status());
        ArrayNode steps = result.putArray("steps");
        for (StepResultRecord step : report.steps()) {
            ObjectNode item = steps.addObject().put("stepId", step.stepId().toString())
                    .put("resultKey", step.resultKey()).put("status", step.status())
                    .put("durationMs", step.durationMs());
            item.set("request", AiPromptSanitizerNode.sanitize(mapper, step.requestSummary()));
            item.set("response", AiPromptSanitizerNode.sanitize(mapper, step.responseSummary()));
            item.set("assertions", AiPromptSanitizerNode.sanitize(mapper, step.assertions()));
            item.set("error", AiPromptSanitizerNode.sanitize(mapper, step.errorSummary()));
        }
        return result;
    }

    private JsonNode createDraftPatch(JsonNode args) {
        String targetType = text(args, "targetType");
        String title = text(args, "title");
        if (!List.of("API_DEFINITION", "API_CASE", "SCENARIO").contains(targetType)) {
            throw new IllegalArgumentException("草稿 Patch 目标类型不支持");
        }
        JsonNode operations = args.path("operations");
        if (!List.of("API_DEFINITION", "API_CASE", "SCENARIO").contains(targetType)
                || title.isBlank() || title.length() > 256 || !operations.isArray() || operations.isEmpty()
                || operations.size() > 100) {
            throw new IllegalArgumentException("草稿 Patch 参数不合法");
        }
        for (int index = 0; index < operations.size(); index++) {
            JsonNode operation = operations.get(index);
            if (!operation.isObject() || !List.of("add", "replace", "remove").contains(operation.path("op").asText())
                    || !operation.path("path").asText().startsWith("/")
                    || ("remove".equals(operation.path("op").asText()) && operation.has("value")
                    && !operation.path("value").isNull())) {
                throw new IllegalArgumentException("草稿 Patch 操作不合法");
            }
        }
        ObjectNode result = mapper.createObjectNode()
                .put("patchId", UUID.randomUUID().toString())
                .put("status", "PENDING_REVIEW")
                .put("targetType", targetType)
                .put("title", AiPromptSanitizer.sanitize(title));
        if (args.hasNonNull("targetId")) result.put("targetId", text(args, "targetId"));
        if (args.hasNonNull("parentId")) result.put("parentId", text(args, "parentId"));
        if (args.has("baseRevision") && !args.path("baseRevision").canConvertToInt()) {
            throw new IllegalArgumentException("草稿 Patch 基线版本不合法");
        }
        if (args.has("baseRevision")) result.put("baseRevision", args.path("baseRevision").asInt());
        result.set("operations", operations.deepCopy());
        return result;
    }

    private ObjectNode patchSchema() {
        ObjectNode schema = schema();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("targetType").put("type", "string");
        properties.putObject("title").put("type", "string");
        properties.putObject("targetId").put("type", "string");
        properties.putObject("parentId").put("type", "string");
        properties.putObject("baseRevision").put("type", "integer");
        properties.putObject("operations").put("type", "array");
        schema.putArray("required").add("targetType").add("title").add("operations");
        return schema;
    }

    private ObjectNode schema() {
        return mapper.createObjectNode().put("type", "object");
    }

    private static UUID requiredUuid(JsonNode args, String field) {
        try {
            return UUID.fromString(args.path(field).asText());
        } catch (Exception exception) {
            throw new IllegalArgumentException("AI 工具参数不合法");
        }
    }

    private static String text(JsonNode args, String field) {
        return args.path(field).asText("").trim();
    }

    private static void ensure(Object value) {
        if (value == null) {
            throw new IllegalStateException("AI 工具依赖未配置");
        }
    }

    private static final class AiPromptSanitizerNode {
        private static final int MAX_TEXT_LENGTH = 4_000;
        private static final Set<String> SENSITIVE_KEYS = Set.of("password", "secret", "token", "apikey",
                "authorization", "cookie", "cookies", "set-cookie", "x-api-key");

        private static JsonNode sanitize(ObjectMapper mapper, JsonNode value) {
            if (value == null || value.isNull()) {
                return mapper.createObjectNode();
            }
            if (value.isTextual()) {
                String sanitized = AiPromptSanitizer.sanitize(value.asText());
                return mapper.getNodeFactory().textNode(sanitized.length() > MAX_TEXT_LENGTH
                        ? sanitized.substring(0, MAX_TEXT_LENGTH) + "…[已截断]" : sanitized);
            }
            if (value.isArray()) {
                ArrayNode result = mapper.createArrayNode();
                value.forEach(child -> result.add(sanitize(mapper, child)));
                return result;
            }
            if (value.isObject()) {
                ObjectNode result = mapper.createObjectNode();
                value.fields().forEachRemaining(entry -> {
                    String key = entry.getKey().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                    if (SENSITIVE_KEYS.stream().anyMatch(key::contains)) {
                        result.put(entry.getKey(), "***");
                    } else {
                        result.set(entry.getKey(), sanitize(mapper, entry.getValue()));
                    }
                });
                return result;
            }
            return value.deepCopy();
        }
    }
}
