package com.autotest.platform.api;

import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.secret.SecretRepository;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ApiCaseService {

    private final ApiCaseRepository cases;
    private final ApiDefinitionRepository definitions;
    private final ProjectRepository projects;
    private final SecretRepository secrets;

    public ApiCaseService(ApiCaseRepository cases, ApiDefinitionRepository definitions,
                          ProjectRepository projects, SecretRepository secrets) {
        this.cases = cases;
        this.definitions = definitions;
        this.projects = projects;
        this.secrets = secrets;
    }

    public List<ApiCaseRecord> list(UUID projectId, UUID definitionId, boolean includeArchived) {
        requireProject(projectId);
        requireDefinition(projectId, definitionId, false);
        return cases.findAll(projectId, definitionId, includeArchived);
    }

    public ApiCaseRecord get(UUID projectId, UUID definitionId, UUID caseId) {
        requireProject(projectId);
        ApiCaseRecord apiCase = cases.findById(projectId, definitionId, caseId);
        if (apiCase == null) {
            throw notFound();
        }
        return apiCase;
    }

    /** 仅执行用例结构校验，不写入数据库，供 AI 草稿预览复用同一套领域规则。 */
    public void validateDraft(UUID projectId, UUID definitionId, ApiCaseWrite request) {
        requireProject(projectId);
        ApiDefinitionRecord definition = requireDefinition(projectId, definitionId, false);
        ApiCaseWrite input = request == null ? new ApiCaseWrite(null, null, null, null, null) : request;
        ApiSpecValidator.name(input.name());
        ApiSpecValidator.validateCase(projectId, definition, input.caseSpec(), input.variables(), input.assertions(), secrets);
    }

    @Transactional
    public ApiCaseRecord create(UUID projectId, UUID definitionId, ApiCaseWrite request, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        ApiDefinitionRecord definition = requireDefinition(projectId, definitionId, true);
        ApiCaseWrite input = request == null ? new ApiCaseWrite(null, null, null, null, null) : request;
        String name = ApiSpecValidator.name(input.name());
        ApiSpecValidator.validateCase(projectId, definition, input.caseSpec(), input.variables(), input.assertions(), secrets);
        if (cases.existsActiveName(projectId, definitionId, name)) {
            throw conflict("NAME_CONFLICT", "接口用例名称已存在");
        }
        try {
            return cases.insert(projectId, definitionId, name, input.caseSpec(), input.variables(), input.assertions(), actorId);
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "接口用例名称已存在");
        }
    }

    @Transactional
    public ApiCaseRecord update(UUID projectId, UUID definitionId, UUID caseId,
                                ApiCaseWrite request, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        ApiDefinitionRecord definition = requireDefinition(projectId, definitionId, true);
        ApiCaseRecord current = requireActive(projectId, definitionId, caseId);
        ApiCaseWrite input = request == null ? new ApiCaseWrite(null, null, null, null, null) : request;
        checkRevision(current, input.revision());
        String name = ApiSpecValidator.name(input.name());
        ApiSpecValidator.validateCase(projectId, definition, input.caseSpec(), input.variables(), input.assertions(), secrets);
        if (cases.existsActiveNameExcluding(projectId, definitionId, name, caseId)) {
            throw conflict("NAME_CONFLICT", "接口用例名称已存在");
        }
        try {
            updateOrConflict(cases.updateDetails(projectId, definitionId, caseId, name, input.caseSpec(),
                    input.variables(), input.assertions(), input.revision(), actorId), current, input.revision());
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "接口用例名称已存在");
        }
        return get(projectId, definitionId, caseId);
    }

    @Transactional
    public ApiCaseRecord archive(UUID projectId, UUID definitionId, UUID caseId,
                                 Integer revision, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        requireDefinition(projectId, definitionId, true);
        ApiCaseRecord current = requireActive(projectId, definitionId, caseId);
        checkRevision(current, revision);
        updateOrConflict(cases.archive(projectId, definitionId, caseId, revision, actorId), current, revision);
        return get(projectId, definitionId, caseId);
    }

    private ApiDefinitionRecord requireDefinition(UUID projectId, UUID definitionId, boolean forWrite) {
        ApiDefinitionRecord definition = definitions.findById(projectId, definitionId);
        if (definition == null) {
            throw notFound();
        }
        if (forWrite && definition.archived()) {
            throw conflict("PARENT_ARCHIVED", "接口定义已归档");
        }
        return definition;
    }

    private ApiCaseRecord requireActive(UUID projectId, UUID definitionId, UUID caseId) {
        ApiCaseRecord apiCase = cases.findActiveByIdForUpdate(projectId, definitionId, caseId);
        if (apiCase == null) {
            throw notFound();
        }
        return apiCase;
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

    private static void writable(ProjectRecord project) {
        if (project.archived()) {
            throw conflict("PROJECT_ARCHIVED", "项目已归档");
        }
    }

    private static void checkRevision(ApiCaseRecord current, Integer revision) {
        if (revision == null || revision != current.revision()) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT",
                    "接口用例版本已变化", Map.of("currentRevision", current.revision(),
                    "requestedRevision", revision == null ? -1 : revision));
        }
    }

    private static void updateOrConflict(int updated, ApiCaseRecord current, int revision) {
        if (updated != 1) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT",
                    "接口用例版本已变化", Map.of("currentRevision", current.revision(),
                    "requestedRevision", revision));
        }
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }
}
