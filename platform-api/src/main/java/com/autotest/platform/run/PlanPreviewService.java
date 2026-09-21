package com.autotest.platform.run;

import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PlanPreviewService {
    private final PlanBuilder plans;
    private final ProjectRepository projects;
    private final ObjectMapper json;
    private final RunService runs;

    public PlanPreviewService(PlanBuilder plans, ProjectRepository projects, ObjectMapper json, RunService runs) {
        this.plans = plans;
        this.projects = projects;
        this.json = json;
        this.runs = runs;
    }

    public PreviewView preview(UUID projectId, JsonNode request) {
        PlanBuilder.rejectClientExecutionPlan(request);
        if (request == null || !request.isObject()) throw invalid("TARGET_INVALID", "预览目标必须是对象");
        ProjectRecord project = projects.findById(projectId);
        if (project == null) throw invalid("TARGET_NOT_FOUND", "项目不存在");
        JsonNode plan = build(projectId, request);
        JsonNode secured = TargetPlanPolicy.attachAndValidate(plan, project.targetAllowlist(), json);
        return view(secured);
    }

    public RunService.CreateResult debug(UUID projectId, JsonNode request, UUID actorId) {
        PlanBuilder.rejectClientExecutionPlan(request);
        if (request == null || !request.isObject()) throw invalid("DEBUG_TARGET_INVALID", "调试目标必须是对象");
        ProjectRecord project = projects.findById(projectId);
        if (project == null) throw invalid("TARGET_NOT_FOUND", "项目不存在");
        JsonNode plan = build(projectId, request);
        JsonNode secured = TargetPlanPolicy.attachAndValidate(plan, project.targetAllowlist(), json);
        UUID definitionId = request.hasNonNull("definitionId") ? uuid(request, "definitionId") : UUID.randomUUID();
        UUID environmentId = uuid(request, "environmentId");
        String idempotencyKey = request.path("idempotencyKey").asText("");
        return runs.createDebug(projectId, environmentId, definitionId, secured, idempotencyKey, actorId);
    }

    public JsonNode build(UUID projectId, JsonNode request) {
        String type = request.path("targetType").asText(request.path("type").asText(""));
        UUID environmentId = uuid(request, "environmentId");
        return switch (type) {
            case "SAVED_DEFINITION", "DEFINITION" -> plans.buildSavedDefinition(projectId,
                    uuid(request, "definitionId"), environmentId);
            case "SAVED_EXECUTABLE", "API_CASE" -> plans.buildSavedCase(projectId,
                    uuid(request, "targetId"), environmentId);
            case "DRAFT_DEFINITION", "DRAFT" -> draft(projectId, request, environmentId);
            default -> throw invalid("TARGET_INVALID", "targetType 必须是定义草稿、已保存定义或已保存用例");
        };
    }

    private JsonNode draft(UUID projectId, JsonNode request, UUID environmentId) {
        JsonNode draft = request.path("draft");
        if (!draft.isObject()) throw invalid("TARGET_INVALID", "draft 必须是对象");
        return plans.buildDraftDefinition(projectId, environmentId, draft.path("method").asText(),
                draft.path("urlTemplate").asText(), draft.get("requestSpec"));
    }

    private PreviewView view(JsonNode plan) {
        JsonNode safe = sanitize(plan);
        return new PreviewView(safe.path("method").asText(), safe.path("baseUrl").asText()
                + safe.path("urlTemplate").asText(), safe.path("query"), safe.path("headers"),
                safe.path("cookies"), safe.path("body"), safe.path("options"), filePreviews(safe));
    }

    private List<PreviewView.FilePreview> filePreviews(JsonNode plan) {
        List<PreviewView.FilePreview> files = new ArrayList<>();
        JsonNode body = plan.path("body");
        JsonNode value = body.path("value");
        JsonNode entries = value.isArray() ? value : value.path("files");
        for (JsonNode file : entries) {
            if (!"FILE".equalsIgnoreCase(file.path("kind").asText()) && !file.has("fileId")) continue;
            files.add(new PreviewView.FilePreview(file.path("fileId").asText(), file.path("originalName").asText(),
                    file.path("size").asLong(0), file.path("sha256").asText(), file.path("mimeType").asText()));
        }
        return files;
    }

    private JsonNode sanitize(JsonNode source) {
        if (source == null || source.isNull()) return json.nullNode();
        if (source.isObject()) {
            ObjectNode result = json.createObjectNode();
            source.fields().forEachRemaining(entry -> {
                String name = entry.getKey().toLowerCase();
                if (name.contains("objectkey") || name.equals("path") || name.contains("password")
                        || name.contains("secret")) return;
                result.set(entry.getKey(), sanitize(entry.getValue()));
            });
            return result;
        }
        if (source.isArray()) {
            ArrayNode result = json.createArrayNode();
            source.forEach(item -> result.add(sanitize(item)));
            return result;
        }
        if (source.isTextual() && source.textValue().contains("${secret:")) {
            return json.getNodeFactory().textNode("${secret}");
        }
        return source.deepCopy();
    }

    private UUID uuid(JsonNode request, String field) {
        try {
            return UUID.fromString(request.path(field).asText());
        } catch (IllegalArgumentException exception) {
            throw invalid("TARGET_INVALID", field + " 必须是 UUID");
        }
    }

    private static ApiDomainException invalid(String code, String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), code, message);
    }
}
