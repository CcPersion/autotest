package com.autotest.platform.ai;

import com.autotest.platform.api.ApiCaseRecord;
import com.autotest.platform.api.ApiCaseRepository;
import com.autotest.platform.api.ApiCaseService;
import com.autotest.platform.api.ApiCaseWrite;
import com.autotest.platform.api.ApiDefinitionRecord;
import com.autotest.platform.api.ApiDefinitionRepository;
import com.autotest.platform.api.ApiDefinitionService;
import com.autotest.platform.api.ApiDefinitionWrite;
import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.scenario.ScenarioRecord;
import com.autotest.platform.scenario.ScenarioRepository;
import com.autotest.platform.scenario.ScenarioService;
import com.autotest.platform.scenario.ScenarioStepWrite;
import com.autotest.platform.scenario.ScenarioWrite;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** 受控的 AI 草稿 Patch：预览阶段不落库，确认阶段复用领域服务的校验和 CAS。 */
@Service
public class AiPatchService {
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Pattern UNTRUSTED = Pattern.compile("\\$\\(|`|<script|</script>|&&|[;|<>]",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> TYPES = Set.of("API_DEFINITION", "API_CASE", "SCENARIO");
    private static final Map<String, Set<String>> ROOT_FIELDS = Map.of(
            "API_DEFINITION", Set.of("moduleId", "name", "method", "urlTemplate", "requestSpec"),
            "API_CASE", Set.of("name", "caseSpec", "variables", "assertions"),
            "SCENARIO", Set.of("name", "description", "variables", "settings", "steps"));
    private static final Set<String> PROTECTED_FIELDS = Set.of("id", "projectId", "revision", "definitionId");
    private static final Set<String> SENSITIVE_NAMES = Set.of("authorization", "cookie", "set-cookie", "x-api-key",
            "apikey", "access_token", "refresh_token", "password", "secret");

    private final ObjectMapper mapper;
    private final ProjectRepository projects;
    private final ApiDefinitionRepository definitions;
    private final ApiCaseRepository cases;
    private final ScenarioRepository scenarios;
    private final ApiDefinitionService definitionService;
    private final ApiCaseService caseService;
    private final ScenarioService scenarioService;
    private final Map<UUID, StoredPatch> previews = new ConcurrentHashMap<>();

    public AiPatchService(ObjectMapper mapper, ProjectRepository projects, ApiDefinitionRepository definitions,
                          ApiCaseRepository cases, ScenarioRepository scenarios,
                          ApiDefinitionService definitionService, ApiCaseService caseService,
                          ScenarioService scenarioService) {
        this.mapper = mapper;
        this.projects = projects;
        this.definitions = definitions;
        this.cases = cases;
        this.scenarios = scenarios;
        this.definitionService = definitionService;
        this.caseService = caseService;
        this.scenarioService = scenarioService;
    }

    public AiPatchPreviewResponse preview(UUID projectId, AiPatchWrite input, UUID actorId) {
        ProjectRecord project = requireProject(projectId);
        if (project.archived()) {
            throw conflict("PROJECT_ARCHIVED", "项目已归档，不能预览修改");
        }
        AiPatchWrite write = requireWrite(input);
        String type = write.targetType().strip().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw invalid("targetType", "INVALID_TARGET_TYPE", "Patch 目标类型不支持");
        }
        if (write.operations() == null || write.operations().isEmpty() || write.operations().size() > 100) {
            throw invalid("operations", "INVALID_OPERATIONS", "Patch 必须包含 1 到 100 个操作");
        }
        JsonNode before;
        int currentRevision;
        UUID effectiveParentId = write.parentId();
        switch (type) {
            case "API_DEFINITION" -> {
                ApiDefinitionRecord current = write.targetId() == null ? null : definitions.findById(projectId, write.targetId());
                if (write.targetId() != null && (current == null || current.archived())) throw notFound();
                currentRevision = current == null ? 0 : current.revision();
                checkBaseRevision(write, currentRevision, current != null);
                before = current == null ? definitionTemplate() : definitionSnapshot(current);
            }
            case "API_CASE" -> {
                if (write.targetId() == null && write.parentId() == null) {
                    throw invalid("parentId", "MISSING_PARENT", "新接口用例必须提供接口定义 id");
                }
                ApiCaseRecord current = write.targetId() == null
                        ? null : cases.findActiveByProjectId(projectId, write.targetId());
                if (write.targetId() != null && (current == null || current.archived())) throw notFound();
                if (current != null) {
                    effectiveParentId = current.apiDefinitionId();
                }
                currentRevision = current == null ? 0 : current.revision();
                checkBaseRevision(write, currentRevision, current != null);
                if (effectiveParentId == null || definitions.findById(projectId, effectiveParentId) == null) {
                    throw invalid("parentId", "INVALID_REFERENCE", "接口定义不存在或不属于当前项目");
                }
                before = current == null ? caseTemplate(effectiveParentId) : caseSnapshot(current);
            }
            case "SCENARIO" -> {
                if (write.parentId() != null) throw invalid("parentId", "PATCH_PATH_NOT_ALLOWED", "场景不能设置父资源");
                ScenarioRecord current = write.targetId() == null ? null : scenarios.findById(projectId, write.targetId());
                if (write.targetId() != null && (current == null || current.archived())) throw notFound();
                currentRevision = current == null ? 0 : current.revision();
                checkBaseRevision(write, currentRevision, current != null);
                before = current == null ? scenarioTemplate() : scenarioSnapshot(current);
            }
            default -> throw invalid("targetType", "INVALID_TARGET_TYPE", "Patch 目标类型不支持");
        }
        JsonNode after = before.deepCopy();
        Set<String> changedPaths = new HashSet<>();
        for (int index = 0; index < write.operations().size(); index++) {
            AiPatchOperation operation = write.operations().get(index);
            String path = "operations[" + index + "]";
            validateOperation(type, operation, path);
            apply(after, operation, path);
            changedPaths.add(operation.path());
        }
        validateUntrustedValues(after, "asset");
        validateAsset(projectId, type, write.targetId(), effectiveParentId, currentRevision, after);

        List<AiPatchChange> changes = new ArrayList<>();
        for (AiPatchOperation operation : write.operations()) {
            JsonNode oldValue = nodeAt(before, operation.path());
            JsonNode newValue = nodeAt(after, operation.path());
            changes.add(new AiPatchChange(operation.path(), changeType(oldValue, newValue), safeCopy(oldValue),
                    safeCopy(newValue), dangerous(operation.path())));
        }
        UUID previewId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(TTL);
        previews.put(previewId, new StoredPatch(previewId, projectId, actorId, type, write.title().strip(), write.targetId(),
                effectiveParentId, currentRevision, after.deepCopy(), expiresAt));
        return new AiPatchPreviewResponse(previewId, projectId, type, write.title().strip(), write.targetId(),
                effectiveParentId, write.baseRevision(), currentRevision, List.copyOf(changes), List.of(), List.of(),
                true, expiresAt);
    }

    @Transactional
    public AiPatchConfirmResponse confirm(UUID projectId, AiPatchConfirmRequest request, UUID actorId) {
        if (request == null || request.previewId() == null) throw invalid("previewId", "INVALID_PREVIEW", "预览令牌不能为空");
        StoredPatch stored = previews.get(request.previewId());
        if (stored == null || !stored.projectId().equals(projectId) || !stored.actorId().equals(actorId)
                || stored.expiresAt().isBefore(Instant.now())) {
            previews.remove(request.previewId());
            throw new ApiDomainException(HttpStatus.NOT_FOUND.value(), "PATCH_NOT_FOUND", "Patch 预览不存在或已过期");
        }
        ProjectRecord project = requireProject(projectId);
        if (project.archived()) throw conflict("PROJECT_ARCHIVED", "项目已归档，不能确认 Patch");
        Object result;
        int revision;
        switch (stored.targetType()) {
            case "API_DEFINITION" -> {
                ApiDefinitionRecord current = stored.targetId() == null ? null : definitions.findById(projectId, stored.targetId());
                ensureCurrentRevision(current == null ? 0 : current.revision(), stored.currentRevision());
                ApiDefinitionWrite write = definitionWrite(stored.asset(), stored.targetId() == null ? null : stored.currentRevision());
                result = stored.targetId() == null ? definitionService.create(projectId, write, actorId)
                        : definitionService.update(projectId, stored.targetId(), write, actorId);
                revision = ((ApiDefinitionRecord) result).revision();
            }
            case "API_CASE" -> {
                ApiCaseRecord current = stored.targetId() == null ? null : cases.findActiveByProjectId(projectId, stored.targetId());
                ensureCurrentRevision(current == null ? 0 : current.revision(), stored.currentRevision());
                UUID definitionId = UUID.fromString(stored.asset().path("definitionId").asText(stored.parentId().toString()));
                ApiCaseWrite write = caseWrite(stored.asset(), stored.targetId() == null ? null : stored.currentRevision());
                result = stored.targetId() == null ? caseService.create(projectId, definitionId, write, actorId)
                        : caseService.update(projectId, definitionId, stored.targetId(), write, actorId);
                revision = ((ApiCaseRecord) result).revision();
            }
            case "SCENARIO" -> {
                ScenarioRecord current = stored.targetId() == null ? null : scenarios.findById(projectId, stored.targetId());
                ensureCurrentRevision(current == null ? 0 : current.revision(), stored.currentRevision());
                ScenarioWrite write = scenarioWrite(stored.asset(), stored.targetId() == null ? null : stored.currentRevision());
                result = stored.targetId() == null ? scenarioService.create(projectId, write, actorId)
                        : scenarioService.update(projectId, stored.targetId(), write, actorId);
                revision = ((ScenarioRecord) result).revision();
            }
            default -> throw invalid("targetType", "INVALID_TARGET_TYPE", "Patch 目标类型不支持");
        }
        if (!previews.remove(request.previewId(), stored)) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "PATCH_ALREADY_CONFIRMED", "Patch 已被确认");
        }
        return new AiPatchConfirmResponse(stored.targetType(), mapper.valueToTree(result), revision);
    }

    private void validateAsset(UUID projectId, String type, UUID targetId, UUID parentId, int revision, JsonNode asset) {
        try {
            switch (type) {
                case "API_DEFINITION" -> definitionService.validateDraft(projectId, definitionWrite(asset, targetId == null ? null : revision));
                case "API_CASE" -> caseService.validateDraft(projectId, parentId,
                        caseWrite(asset, targetId == null ? null : revision));
                case "SCENARIO" -> scenarioService.validateDraft(projectId, scenarioWrite(asset,
                        targetId == null ? null : revision));
                default -> throw invalid("targetType", "INVALID_TARGET_TYPE", "Patch 目标类型不支持");
            }
        } catch (ApiDomainException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("asset", "INVALID_PATCH_ASSET", "Patch 生成的资产不符合平台结构");
        }
    }

    private void validateOperation(String type, AiPatchOperation operation, String path) {
        if (operation == null) throw invalid(path, "INVALID_OPERATION", "Patch 操作不能为空");
        String op = operation.op() == null ? "" : operation.op().strip().toLowerCase(Locale.ROOT);
        if (!Set.of("add", "replace", "remove").contains(op)) throw invalid(path + ".op", "INVALID_OPERATION", "只支持 add、replace、remove");
        if (operation.path() == null || !operation.path().startsWith("/") || operation.path().equals("/")) {
            throw invalid(path + ".path", "INVALID_PATH", "Patch path 必须是 JSON Pointer");
        }
        String first = decode(operation.path().substring(1).split("/", 2)[0]);
        if (PROTECTED_FIELDS.contains(first) || !ROOT_FIELDS.get(type).contains(first)) {
            throw invalid(path + ".path", "PATCH_PATH_NOT_ALLOWED", "Patch 不能修改该字段");
        }
        if ("remove".equals(op) && operation.value() != null && !operation.value().isNull()) {
            throw invalid(path + ".value", "INVALID_OPERATION", "remove 操作不能携带 value");
        }
        validateUntrustedValues(operation.value(), path + ".value");
        if (sensitivePath(operation.path()) && operation.value() != null && operation.value().isTextual()
                && !secretTemplate(operation.value().asText())) {
            throw invalid(path + ".value", "INVALID_SECRET_REFERENCE", "敏感字段必须使用密钥引用");
        }
    }

    private static void apply(JsonNode root, AiPatchOperation operation, String path) {
        String[] raw = operation.path().substring(1).split("/", -1);
        String[] parts = Arrays.stream(raw).map(AiPatchService::decode).toArray(String[]::new);
        JsonNode parent = root;
        for (int index = 0; index < parts.length - 1; index++) {
            JsonNode child = parent.get(parts[index]);
            if (child == null || child.isNull()) throw invalid(path + ".path", "PATH_NOT_FOUND", "Patch path 的父节点不存在");
            parent = child;
        }
        String leaf = parts[parts.length - 1];
        String op = operation.op().toLowerCase(Locale.ROOT);
        JsonNode value = operation.value() == null ? JsonNodeFactory.instance.nullNode() : operation.value().deepCopy();
        if (parent.isObject()) {
            ObjectNode object = (ObjectNode) parent;
            if ("remove".equals(op)) {
                if (!object.has(leaf)) throw invalid(path + ".path", "PATH_NOT_FOUND", "待删除字段不存在");
                object.remove(leaf);
            } else {
                object.set(leaf, value);
            }
        } else if (parent.isArray()) {
            ArrayNode array = (ArrayNode) parent;
            int index = "-".equals(leaf) ? array.size() : parseIndex(leaf, path);
            if ("add".equals(op) && index == array.size()) array.add(value);
            else if ("add".equals(op)) array.insert(index, value);
            else if ("replace".equals(op) && index < array.size()) array.set(index, value);
            else if ("remove".equals(op) && index < array.size()) array.remove(index);
            else throw invalid(path + ".path", "PATH_NOT_FOUND", "数组索引不存在");
        } else {
            throw invalid(path + ".path", "PATH_NOT_FOUND", "Patch path 父节点不可修改");
        }
    }

    private static int parseIndex(String value, String path) {
        try {
            int index = Integer.parseInt(value);
            if (index < 0) throw new NumberFormatException();
            return index;
        } catch (NumberFormatException exception) {
            throw invalid(path + ".path", "INVALID_INDEX", "数组索引不合法");
        }
    }

    private static JsonNode nodeAt(JsonNode root, String path) {
        JsonNode node = root.at(path);
        return node.isMissingNode() ? null : node;
    }

    private static String changeType(JsonNode oldValue, JsonNode newValue) {
        if (oldValue == null && newValue != null) return "ADDED";
        if (oldValue != null && newValue == null) return "REMOVED";
        return oldValue == null || !oldValue.equals(newValue) ? "MODIFIED" : "UNCHANGED";
    }

    private static boolean dangerous(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        return normalized.contains("secret") || normalized.contains("authorization") || normalized.contains("cookie")
                || normalized.contains("headers") || normalized.contains("steps") || normalized.contains("settings");
    }

    private void validateUntrustedValues(JsonNode node, String path) {
        if (node == null || node.isNull()) return;
        if (node.isTextual() && UNTRUSTED.matcher(node.asText()).find()) {
            throw invalid(path, "UNTRUSTED_PATCH_VALUE", "Patch 内容不能包含脚本或 Shell 片段");
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) validateUntrustedValues(node.get(i), path + "[" + i + "]");
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> validateUntrustedValues(entry.getValue(), path + "." + entry.getKey()));
        }
    }

    private static boolean sensitivePath(String path) {
        String normalized = path.toLowerCase(Locale.ROOT).replace("-", "_");
        return SENSITIVE_NAMES.stream().anyMatch(normalized::contains);
    }

    private static boolean secretTemplate(String value) {
        return value.matches("\\$\\{secret:[A-Za-z0-9][A-Za-z0-9._-]*}")
                || value.matches("(?:Bearer|Basic) \\$\\{secret:[A-Za-z0-9][A-Za-z0-9._-]*}");
    }

    private static JsonNode safeCopy(JsonNode node) {
        return node == null ? null : node.deepCopy();
    }

    private static AiPatchWrite requireWrite(AiPatchWrite write) {
        if (write == null || write.title() == null || write.title().isBlank() || write.title().length() > 256
                || write.targetType() == null) {
            throw invalid("patch", "INVALID_PATCH", "Patch 标题和目标类型不能为空");
        }
        return write;
    }

    private static void checkBaseRevision(AiPatchWrite write, int currentRevision, boolean existing) {
        if (existing && (write.baseRevision() == null || write.baseRevision() != currentRevision)) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT", "草稿基线版本已变化",
                    Map.of("currentRevision", currentRevision, "requestedRevision",
                            write.baseRevision() == null ? -1 : write.baseRevision()));
        }
        if (!existing && write.baseRevision() != null && write.baseRevision() != 0) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT", "新资产基线版本必须为 0");
        }
    }

    private static void ensureCurrentRevision(int currentRevision, int expectedRevision) {
        if (currentRevision != expectedRevision) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT", "草稿基线版本已变化",
                    Map.of("currentRevision", currentRevision, "requestedRevision", expectedRevision));
        }
    }

    private ProjectRecord requireProject(UUID projectId) {
        ProjectRecord project = projects.findById(projectId);
        if (project == null) throw notFound();
        return project;
    }

    private static ObjectNode definitionTemplate() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.putNull("moduleId").put("name", "").put("method", "GET").put("urlTemplate", "");
        ObjectNode spec = node.putObject("requestSpec");
        spec.putArray("pathParams"); spec.putArray("query"); spec.putArray("headers"); spec.putArray("cookies");
        spec.putObject("body").put("type", "NONE"); spec.putObject("options");
        return node;
    }

    private static ObjectNode caseTemplate(UUID definitionId) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("definitionId", definitionId.toString()).put("name", "");
        ObjectNode spec = node.putObject("caseSpec"); spec.putObject("pathParams"); spec.putObject("query");
        spec.putObject("headers"); spec.putObject("cookies"); spec.putObject("body").put("type", "NONE");
        spec.putArray("extractors"); spec.putArray("dataRows"); spec.putObject("dataRowOptions").put("continueOnFailure", true);
        node.putObject("variables"); node.putArray("assertions");
        return node;
    }

    private static ObjectNode scenarioTemplate() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("name", "").putNull("description").putObject("variables").putObject("settings").putArray("steps");
        return node;
    }

    private ObjectNode definitionSnapshot(ApiDefinitionRecord record) {
        ObjectNode node = definitionTemplate();
        if (record.moduleId() == null) node.putNull("moduleId"); else node.put("moduleId", record.moduleId().toString());
        node.put("name", record.name()).put("method", record.method()).put("urlTemplate", record.urlTemplate());
        node.set("requestSpec", record.requestSpec().deepCopy());
        return node;
    }

    private ObjectNode caseSnapshot(ApiCaseRecord record) {
        ObjectNode node = caseTemplate(record.apiDefinitionId());
        node.put("name", record.name());
        node.set("caseSpec", record.caseSpec().deepCopy());
        node.set("variables", record.variables().deepCopy());
        node.set("assertions", record.assertions().deepCopy());
        return node;
    }

    private ObjectNode scenarioSnapshot(ScenarioRecord record) {
        ObjectNode node = scenarioTemplate();
        node.put("name", record.name());
        if (record.description() == null) node.putNull("description"); else node.put("description", record.description());
        node.set("variables", record.variables().deepCopy());
        node.set("settings", record.settings().deepCopy());
        ArrayNode steps = node.putArray("steps");
        record.steps().forEach(step -> steps.add(mapper.valueToTree(step)));
        return node;
    }

    private ApiDefinitionWrite definitionWrite(JsonNode asset, Integer revision) {
        UUID moduleId = uuidOrNull(asset.get("moduleId"));
        return new ApiDefinitionWrite(moduleId, asset.path("name").asText(null), asset.path("method").asText(null),
                asset.path("urlTemplate").asText(null), asset.get("requestSpec"), revision);
    }

    private ApiCaseWrite caseWrite(JsonNode asset, Integer revision) {
        return new ApiCaseWrite(asset.path("name").asText(null), asset.get("caseSpec"), asset.get("variables"),
                asset.get("assertions"), revision);
    }

    private ScenarioWrite scenarioWrite(JsonNode asset, Integer revision) {
        List<ScenarioStepWrite> steps = new ArrayList<>();
        JsonNode stepNode = asset.get("steps");
        if (stepNode != null && stepNode.isArray()) {
            for (JsonNode step : stepNode) steps.add(mapper.convertValue(step, ScenarioStepWrite.class));
        }
        return new ScenarioWrite(asset.path("name").asText(null), asset.path("description").isNull() ? null : asset.path("description").asText(null),
                asset.get("variables"), asset.get("settings"), steps, revision);
    }

    private static UUID uuidOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) return null;
        try { return UUID.fromString(node.asText()); }
        catch (IllegalArgumentException exception) { throw invalid("asset", "INVALID_UUID", "资源 id 不合法"); }
    }

    private static String decode(String value) {
        return value.replace("~1", "/").replace("~0", "~");
    }

    private static ApiDomainException invalid(String path, String code, String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), code, message,
                Map.of("fieldErrors", List.of(Map.of("path", path, "code", code, "message", message))));
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }

    private record StoredPatch(UUID previewId, UUID projectId, UUID actorId, String targetType, String title,
                               UUID targetId, UUID parentId, int currentRevision, JsonNode asset, Instant expiresAt) {
    }
}
