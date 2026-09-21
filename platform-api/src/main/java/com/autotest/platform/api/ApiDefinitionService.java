package com.autotest.platform.api;

import com.autotest.platform.module.ModuleRecord;
import com.autotest.platform.module.ModuleRepository;
import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.secret.SecretRepository;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ApiDefinitionService {

    private final ApiDefinitionRepository definitions;
    private final ProjectRepository projects;
    private final ModuleRepository modules;
    private final SecretRepository secrets;
    private final ApiCaseRepository cases;

    public ApiDefinitionService(ApiDefinitionRepository definitions, ProjectRepository projects,
                                ModuleRepository modules, SecretRepository secrets, ApiCaseRepository cases) {
        this.definitions = definitions;
        this.projects = projects;
        this.modules = modules;
        this.secrets = secrets;
        this.cases = cases;
    }

    public List<ApiDefinitionRecord> list(UUID projectId, UUID moduleId, boolean includeArchived) {
        requireProject(projectId);
        if (moduleId != null && modules.findActiveById(projectId, moduleId) == null) {
            throw notFound();
        }
        return definitions.findAll(projectId, moduleId, includeArchived);
    }

    public ApiDefinitionRecord get(UUID projectId, UUID definitionId) {
        requireProject(projectId);
        ApiDefinitionRecord definition = definitions.findById(projectId, definitionId);
        if (definition == null) {
            throw notFound();
        }
        return definition;
    }

    /** 仅执行定义结构校验，不写入数据库，供 AI 草稿预览复用同一套领域规则。 */
    public void validateDraft(UUID projectId, ApiDefinitionWrite request) {
        ProjectRecord project = requireProject(projectId);
        if (project.archived()) {
            throw conflict("PROJECT_ARCHIVED", "项目已归档，不能修改接口定义");
        }
        ApiDefinitionWrite input = request == null
                ? new ApiDefinitionWrite(null, null, null, null, null, null) : request;
        ApiSpecValidator.name(input.name());
        module(projectId, input.moduleId());
        String method = normalizeMethod(input.method());
        ApiSpecValidator.validateDefinition(projectId, method, input.urlTemplate(), input.requestSpec(), secrets);
    }

    @Transactional
    public ApiDefinitionRecord create(UUID projectId, ApiDefinitionWrite request, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        ApiDefinitionWrite input = request == null ? new ApiDefinitionWrite(null, null, null, null, null, null) : request;
        String name = ApiSpecValidator.name(input.name());
        ModuleRecord module = module(projectId, input.moduleId());
        String method = normalizeMethod(input.method());
        ApiSpecValidator.validateDefinition(projectId, method, input.urlTemplate(), input.requestSpec(), secrets);
        if (definitions.existsActiveName(projectId, input.moduleId(), name)) {
            throw conflict("NAME_CONFLICT", "接口定义名称已存在");
        }
        try {
            return definitions.insert(projectId, module == null ? null : module.id(), name, method,
                    input.urlTemplate(), input.requestSpec(), actorId);
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "接口定义名称已存在");
        }
    }

    @Transactional
    public ApiDefinitionRecord update(UUID projectId, UUID definitionId, ApiDefinitionWrite request, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        ApiDefinitionRecord current = requireActive(projectId, definitionId);
        checkRevision(current, request == null ? null : request.revision());
        ApiDefinitionWrite input = request == null ? new ApiDefinitionWrite(null, null, null, null, null, null) : request;
        String name = ApiSpecValidator.name(input.name());
        ModuleRecord module = module(projectId, input.moduleId());
        String method = normalizeMethod(input.method());
        ApiSpecValidator.validateDefinition(projectId, method, input.urlTemplate(), input.requestSpec(), secrets);
        if (definitions.existsActiveNameExcluding(projectId, input.moduleId(), name, definitionId)) {
            throw conflict("NAME_CONFLICT", "接口定义名称已存在");
        }
        ApiDefinitionRecord proposed = new ApiDefinitionRecord(current.id(), current.projectId(),
                module == null ? null : module.id(), name, method, input.urlTemplate(), input.requestSpec(),
                current.revision(), current.archived(), current.createdAt(), current.updatedAt());
        for (ApiCaseRecord apiCase : cases.findAll(projectId, definitionId, false)) {
            try {
                ApiSpecValidator.validateCase(projectId, proposed, apiCase.caseSpec(), apiCase.variables(),
                        apiCase.assertions(), secrets, true);
            } catch (ApiDomainException exception) {
                throw childIncompatible(apiCase, exception);
            }
        }
        try {
            updateOrConflict(definitions.updateDetails(projectId, definitionId,
                    module == null ? null : module.id(), name, method, input.urlTemplate(), input.requestSpec(),
                    input.revision(), actorId), current, input.revision());
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "接口定义名称已存在");
        }
        return get(projectId, definitionId);
    }

    @Transactional
    public ApiDefinitionRecord archive(UUID projectId, UUID definitionId, Integer revision, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        ApiDefinitionRecord current = requireActive(projectId, definitionId);
        checkRevision(current, revision);
        updateOrConflict(definitions.archive(projectId, definitionId, revision, actorId), current, revision);
        return get(projectId, definitionId);
    }

    private ModuleRecord module(UUID projectId, UUID moduleId) {
        if (moduleId == null) {
            return null;
        }
        ModuleRecord module = modules.findActiveById(projectId, moduleId);
        if (module == null) {
            throw notFound();
        }
        return module;
    }

    private ProjectRecord requireProject(UUID projectId) {
        ProjectRecord project = projects.findById(projectId);
        if (project == null) {
            throw notFound();
        }
        return project;
    }

    private ProjectRecord lockProject(UUID projectId) {
        ProjectRecord project = projects.findByIdForUpdate(projectId);
        if (project == null) {
            throw notFound();
        }
        return project;
    }

    private ApiDefinitionRecord requireActive(UUID projectId, UUID definitionId) {
        ApiDefinitionRecord definition = definitions.findActiveByIdForUpdate(projectId, definitionId);
        if (definition == null) {
            throw notFound();
        }
        return definition;
    }

    private static void writable(ProjectRecord project) {
        if (project.archived()) {
            throw conflict("PROJECT_ARCHIVED", "项目已归档");
        }
    }

    private static void checkRevision(ApiDefinitionRecord current, Integer revision) {
        if (revision == null || revision != current.revision()) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT",
                    "接口定义版本已变化", Map.of("currentRevision", current.revision(),
                    "requestedRevision", revision == null ? -1 : revision));
        }
    }

    private static void updateOrConflict(int updated, ApiDefinitionRecord current, int revision) {
        if (updated != 1) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT",
                    "接口定义版本已变化", Map.of("currentRevision", current.revision(),
                    "requestedRevision", revision));
        }
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private static String normalizeMethod(String method) {
        return method == null ? null : method.toUpperCase(Locale.ROOT);
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }

    private static ApiDomainException childIncompatible(ApiCaseRecord apiCase, ApiDomainException cause) {
        String path = "caseSpec";
        String code = cause.code();
        if (cause.details() instanceof Map<?, ?> errorDetails
                && errorDetails.get("fieldErrors") instanceof List<?> fieldErrors
                && !fieldErrors.isEmpty()
                && fieldErrors.get(0) instanceof Map<?, ?> fieldError) {
            if (fieldError.get("path") instanceof String value && !value.isBlank()) {
                path = value;
            }
            if (fieldError.get("code") instanceof String value && !value.isBlank()) {
                code = value;
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("caseId", apiCase.id());
        if (apiCase.name() != null) {
            details.put("name", apiCase.name());
        }
        details.put("fieldErrors", List.of(Map.of("path", path, "code", code)));
        return new ApiDomainException(HttpStatus.CONFLICT.value(), "CHILD_CASE_INCOMPATIBLE",
                "接口定义变更与已有用例不兼容", details);
    }
}
