package com.autotest.platform.run;

import com.autotest.platform.api.ApiCaseRecord;
import com.autotest.platform.api.ApiCaseRepository;
import com.autotest.platform.api.ApiDefinitionRecord;
import com.autotest.platform.api.ApiSpecValidator;
import com.autotest.platform.api.ApiDefinitionRepository;
import com.autotest.platform.environment.EnvironmentRecord;
import com.autotest.platform.environment.EnvironmentRepository;
import com.autotest.platform.file.FileAssetRecord;
import com.autotest.platform.file.FileAssetRepository;
import com.autotest.platform.secret.SecretRepository;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The single server-side source of executable HTTP plans for preview, debug and
 * saved API-case runs.  It deliberately accepts references/drafts, never a
 * client-produced executionPlan.
 */
@Component
public class PlanBuilder {
    private final ApiDefinitionRepository definitions;
    private final ApiCaseRepository cases;
    private final EnvironmentRepository environments;
    private final ObjectMapper json;
    private final FileAssetRepository assets;
    private final SecretRepository secrets;

    public PlanBuilder(ApiDefinitionRepository definitions, ApiCaseRepository cases,
                       EnvironmentRepository environments, ObjectMapper json) {
        this(definitions, cases, environments, json, null, null);
    }

    public PlanBuilder(ApiDefinitionRepository definitions, ApiCaseRepository cases,
                       EnvironmentRepository environments, ObjectMapper json, FileAssetRepository assets) {
        this(definitions, cases, environments, json, assets, null);
    }

    @Autowired
    public PlanBuilder(ApiDefinitionRepository definitions, ApiCaseRepository cases,
                       EnvironmentRepository environments, ObjectMapper json, FileAssetRepository assets,
                       SecretRepository secrets) {
        this.definitions = definitions;
        this.cases = cases;
        this.environments = environments;
        this.json = json;
        this.assets = assets;
        this.secrets = secrets;
    }

    public JsonNode buildSavedCase(UUID projectId, UUID caseId, UUID environmentId) {
        ApiCaseRecord apiCase = cases.findActiveByProjectId(projectId, caseId);
        if (apiCase == null) throw invalid("TARGET_NOT_FOUND", "接口用例不存在或已归档");
        ApiDefinitionRecord definition = definitions.findById(projectId, apiCase.apiDefinitionId());
        if (definition == null || definition.archived()) throw invalid("TARGET_NOT_FOUND", "接口定义不存在或已归档");
        EnvironmentRecord environment = environment(projectId, environmentId);
        return buildCase(projectId, environment, definition, apiCase, "case-" + caseId, caseId.toString());
    }

    public JsonNode buildSavedDefinition(UUID projectId, UUID definitionId, UUID environmentId) {
        ApiDefinitionRecord definition = definitions.findById(projectId, definitionId);
        if (definition == null || definition.archived()) throw invalid("TARGET_NOT_FOUND", "接口定义不存在或已归档");
        EnvironmentRecord environment = environment(projectId, environmentId);
        return buildDefinition(projectId, environment, definition, "definition-" + definition.id(), null);
    }

    public JsonNode buildDraftDefinition(UUID projectId, UUID environmentId, String method,
                                         String urlTemplate, JsonNode requestSpec) {
        JsonNode draftSpec = requestSpec == null ? json.createObjectNode() : requestSpec;
        ApiSpecValidator.validateDefinitionForExecution(projectId, method, urlTemplate, draftSpec, secrets);
        EnvironmentRecord environment = environment(projectId, environmentId);
        ApiDefinitionRecord draft = new ApiDefinitionRecord(UUID.randomUUID(), projectId, null,
                "draft", method == null ? "" : method.toUpperCase(Locale.ROOT), urlTemplate,
                draftSpec, 0, false, null, null);
        return buildDefinition(projectId, environment, draft, "draft-" + UUID.randomUUID(), null);
    }

    public EnvironmentRecord environment(UUID projectId, UUID environmentId) {
        EnvironmentRecord environment = environments.findById(projectId, environmentId);
        if (environment == null) throw invalid("TARGET_NOT_FOUND", "环境不存在");
        if (environment.archived()) throw invalid("TARGET_INVALID", "环境已归档");
        return environment;
    }

    public static void rejectClientExecutionPlan(JsonNode request) {
        rejectForbidden(request, true);
    }

    private static void rejectForbidden(JsonNode node, boolean root) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (key.equals("executionplan") || key.equals("objectkey") || (root && key.equals("path"))) {
                    throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "PLAN_TAMPERED", "PLAN_TAMPERED");
                }
                rejectForbidden(entry.getValue(), false);
            }
        } else if (node.isArray()) {
            node.forEach(value -> rejectForbidden(value, false));
        }
    }

    private ObjectNode buildCase(UUID projectId, EnvironmentRecord environment,
                                 ApiDefinitionRecord definition, ApiCaseRecord apiCase,
                                 String planId, String stepId) {
        ObjectNode plan = buildDefinition(projectId, environment, definition, planId, stepId);
        JsonNode caseSpec = apiCase.caseSpec();
        plan.set("query", mergeArray(plan.path("query"), caseSpec.path("query"), false));
        plan.set("headers", mergeArray(plan.path("headers"), caseSpec.path("headers"), true));
        plan.set("cookies", mergeArray(plan.path("cookies"), caseSpec.path("cookies"), true));
        plan.put("urlTemplate", resolvePath(plan.path("urlTemplate").asText(),
                definition.requestSpec().path("pathParams"), caseSpec.path("pathParams")));
        JsonNode body = caseSpec.path("body");
        if (body.isObject() && body.has("type") && body.path("type").asText().equals(plan.path("body").path("type").asText())) {
            plan.set("body", body.deepCopy());
            attachFileSnapshots(projectId, plan.path("body"));
        }
        plan.set("variables", apiCase.variables().deepCopy());
        plan.set("assertions", apiCase.assertions().deepCopy());
        JsonNode extractors = caseSpec.get("extractors");
        plan.set("extractors", extractors == null ? json.createArrayNode() : extractors.deepCopy());
        ObjectNode options = mergeOptions(environment.requestOptions(), definition.requestSpec().path("options"));
        options = mergeCaseOptions(options, caseSpec.path("options"));
        plan.set("options", options);
        attachFileSnapshots(projectId, plan);
        copyRuntimeOptions(plan, plan.path("options"));
        ObjectNode scopes = plan.putObject("variableScopes");
        scopes.set("environment", environment.variables().deepCopy());
        scopes.set("caseVariables", apiCase.variables().deepCopy());
        scopes.putObject("extracted");
        return plan;
    }

    private ObjectNode buildDefinition(UUID projectId, EnvironmentRecord environment,
                                       ApiDefinitionRecord definition, String planId, String stepId) {
        ObjectNode plan = json.createObjectNode();
        plan.put("jmeterVersion", "5.6.3");
        plan.put("projectId", projectId.toString());
        plan.put("planId", planId);
        if (stepId != null) plan.put("stepId", stepId);
        plan.put("baseUrl", environment.baseUrl());
        plan.put("method", definition.method());
        plan.put("urlTemplate", definition.urlTemplate());
        JsonNode spec = definition.requestSpec();
        plan.set("pathParams", spec.path("pathParams").deepCopy());
        plan.set("query", spec.path("query").deepCopy());
        plan.set("headers", mergeEnvironmentHeaders(environment.requestOptions(), spec.path("headers")));
        plan.set("cookies", spec.path("cookies").deepCopy());
        plan.set("body", spec.path("body").deepCopy());
        plan.set("options", mergeOptions(environment.requestOptions(), spec.path("options")));
        attachFileSnapshots(projectId, plan);
        copyRuntimeOptions(plan, plan.path("options"));
        plan.set("variables", json.createObjectNode());
        plan.set("assertions", json.createArrayNode());
        plan.set("extractors", json.createArrayNode());
        ObjectNode scopes = plan.putObject("variableScopes");
        scopes.set("environment", environment.variables().deepCopy());
        return plan;
    }

    private void attachFileSnapshots(UUID projectId, JsonNode plan) {
        if (assets == null || plan == null || !plan.isObject()) return;
        JsonNode body = plan.path("body");
        if (body.isObject() && "MULTIPART".equals(body.path("type").asText())) {
            attachMultipartSnapshots(projectId, body);
        }
        JsonNode certificate = plan.path("options").path("clientCertificate");
        if (certificate instanceof ObjectNode certificateObject && certificate.hasNonNull("fileId")) {
            attachAssetSnapshot(projectId, certificateObject, "fileId", "fileSnapshot", "PKCS12");
        }
    }

    private void attachMultipartSnapshots(UUID projectId, JsonNode body) {
        JsonNode value = body.path("value");
        JsonNode files = value.isArray() ? value : value.path("files");
        if (!files.isArray()) return;
        for (JsonNode item : files) {
            if (!(item instanceof ObjectNode file)) continue;
            // The compact array form contains both text and file parts. Only
            // file parts carry a fileId; validating a text part as a file
            // would turn an otherwise valid multipart plan into
            // FILE_NOT_ACTIVE before preview or execution.
            if (!"FILE".equalsIgnoreCase(file.path("kind").asText())
                    && !file.hasNonNull("fileId")) continue;
            attachAssetSnapshot(projectId, file, "fileId", null, "REQUEST_FILE");
        }
    }

    private void attachAssetSnapshot(UUID projectId, ObjectNode reference, String idField,
                                     String snapshotField, String expectedKind) {
        UUID fileId;
        try {
            fileId = UUID.fromString(reference.path(idField).asText());
        } catch (IllegalArgumentException exception) {
            throw invalid("FILE_NOT_ACTIVE", "文件引用无效");
        }
        FileAssetRecord asset = assets.find(projectId, fileId);
        if (asset == null || !"ACTIVE".equals(asset.status()) || !expectedKind.equals(asset.kind())) {
            throw invalid("FILE_NOT_ACTIVE", "文件不存在、类型不匹配或已归档");
        }
        ObjectNode target = snapshotField == null ? reference : reference.putObject(snapshotField);
        target.put("fileId", asset.id().toString());
        target.put("originalName", asset.originalName());
        target.put("size", asset.size());
        target.put("sha256", asset.sha256());
        target.put("mimeType", asset.mimeType());
    }

    private ArrayNode mergeEnvironmentHeaders(JsonNode environmentOptions, JsonNode declared) {
        ArrayNode result = json.createArrayNode();
        JsonNode defaults = environmentOptions == null ? null : environmentOptions.path("defaultHeaders");
        if (defaults != null && defaults.isArray()) defaults.forEach(value -> result.add(value.deepCopy()));
        return mergeArray(result, declared, true);
    }

    private ArrayNode mergeArray(JsonNode declared, JsonNode overrides, boolean caseInsensitive) {
        ArrayNode result = json.createArrayNode();
        if (declared != null && declared.isArray()) declared.forEach(value -> result.add(value.deepCopy()));
        if (overrides != null && overrides.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = overrides.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                for (JsonNode item : result) {
                    if (item.path("name").asText().equals(entry.getKey())
                            || (caseInsensitive && item.path("name").asText().equalsIgnoreCase(entry.getKey()))) {
                        ((ObjectNode) item).set("value", entry.getValue().deepCopy());
                    }
                }
            }
        }
        return result;
    }

    private ObjectNode mergeOptions(JsonNode environment, JsonNode definition) {
        ObjectNode result = json.createObjectNode();
        ObjectNode envOptions = optionsObject(environment);
        ObjectNode defOptions = optionsObject(definition);
        for (String field : new String[]{"followRedirects", "proxy", "clientCertificate",
                "connectTimeoutMillis", "responseTimeoutMillis", "readTimeoutMillis", "totalTimeoutMillis"}) {
            if (envOptions.has(field)) result.set(field, envOptions.get(field).deepCopy());
            if (defOptions.has(field)) result.set(field, defOptions.get(field).deepCopy());
        }
        if (environment != null && environment.isObject() && environment.path("defaultHeaders").isArray()) {
            result.set("defaultHeaders", environment.path("defaultHeaders").deepCopy());
        }
        return result;
    }

    private ObjectNode mergeCaseOptions(ObjectNode base, JsonNode override) {
        if (override != null && override.isObject()) {
            for (String field : new String[]{"connectTimeoutMillis", "responseTimeoutMillis",
                    "readTimeoutMillis", "totalTimeoutMillis"}) {
                if (override.has(field)) base.set(field, override.get(field).deepCopy());
            }
        }
        return base;
    }

    private ObjectNode optionsObject(JsonNode value) {
        if (value == null || !value.isObject()) return json.createObjectNode();
        JsonNode nested = value.get("options");
        return nested != null && nested.isObject() ? nested.deepCopy() : value.deepCopy();
    }

    private void copyRuntimeOptions(ObjectNode plan, JsonNode options) {
        if (options == null || !options.isObject()) return;
        for (String field : new String[]{"followRedirects", "proxy", "clientCertificate",
                "connectTimeoutMillis", "responseTimeoutMillis", "readTimeoutMillis", "totalTimeoutMillis"}) {
            if (options.has(field)) plan.set(field, options.get(field).deepCopy());
        }
    }

    private String resolvePath(String url, JsonNode declared, JsonNode overrides) {
        String resolved = url;
        if (declared != null && declared.isArray()) {
            for (JsonNode item : declared) {
                String name = item.path("name").asText();
                JsonNode value = overrides == null ? null : overrides.get(name);
                if (value == null) value = item.get("value");
                resolved = resolved.replace("{" + name + "}", value == null ? "" : value.asText());
            }
        }
        return resolved;
    }

    private static ApiDomainException invalid(String code, String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), code, message);
    }
}
