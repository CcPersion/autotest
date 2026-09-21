package com.autotest.platform.scenario;

import com.autotest.platform.api.ApiCaseRecord;
import com.autotest.platform.api.ApiCaseRepository;
import com.autotest.platform.environment.JdbcDataSourceRecord;
import com.autotest.platform.environment.JdbcDataSourceRepository;
import com.autotest.platform.environment.RedisDataSourceRecord;
import com.autotest.platform.environment.RedisDataSourceRepository;
import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ScenarioService {
    private final ScenarioRepository scenarios;
    private final ProjectRepository projects;
    private final ApiCaseRepository cases;
    private final JdbcDataSourceRepository dataSources;
    private final RedisDataSourceRepository redisDataSources;

    public ScenarioService(ScenarioRepository scenarios, ProjectRepository projects, ApiCaseRepository cases,
                           JdbcDataSourceRepository dataSources) {
        this(scenarios, projects, cases, dataSources, null);
    }

    @Autowired
    public ScenarioService(ScenarioRepository scenarios, ProjectRepository projects, ApiCaseRepository cases,
                           JdbcDataSourceRepository dataSources, RedisDataSourceRepository redisDataSources) {
        this.scenarios = scenarios;
        this.projects = projects;
        this.cases = cases;
        this.dataSources = dataSources;
        this.redisDataSources = redisDataSources;
    }

    public List<ScenarioRecord> list(UUID projectId, boolean includeArchived) {
        requireProject(projectId);
        return scenarios.findAll(projectId, includeArchived).stream().map(scenarios::withSteps).toList();
    }

    public ScenarioRecord get(UUID projectId, UUID scenarioId) {
        requireProject(projectId);
        ScenarioRecord scenario = scenarios.findById(projectId, scenarioId);
        if (scenario == null) throw notFound();
        return scenarios.withSteps(scenario);
    }

    /** 仅执行场景及其跨资产引用校验，不写入数据库，供 AI 草稿预览复用领域规则。 */
    public void validateDraft(UUID projectId, ScenarioWrite request) {
        requireProject(projectId);
        ScenarioValidator.validate(request);
        validateReferences(projectId, request.steps());
    }

    @Transactional
    public ScenarioRecord create(UUID projectId, ScenarioWrite request, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        ScenarioWrite input = requireRequest(request);
        ScenarioValidator.validate(input);
        String name = ScenarioValidator.name(input.name());
        validateReferences(projectId, input.steps());
        if (scenarios.existsActiveName(projectId, name)) throw conflict("NAME_CONFLICT", "场景名称已存在");
        try {
            ScenarioRecord created = scenarios.insert(projectId, name, input.description(), input.variables(), input.settings(), actorId);
            replaceSteps(projectId, created.id(), input.steps());
            return get(projectId, created.id());
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "场景名称已存在");
        }
    }

    @Transactional
    public ScenarioRecord update(UUID projectId, UUID scenarioId, ScenarioWrite request, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        ScenarioRecord current = scenarios.findActiveByIdForUpdate(projectId, scenarioId);
        if (current == null) throw notFound();
        ScenarioWrite input = requireRequest(request);
        ScenarioValidator.validate(input);
        if (input.revision() == null || input.revision() != current.revision()) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT", "场景版本已变化",
                    Map.of("currentRevision", current.revision(), "requestedRevision", input.revision() == null ? -1 : input.revision()));
        }
        String name = ScenarioValidator.name(input.name());
        validateReferences(projectId, input.steps());
        if (scenarios.existsActiveNameExcluding(projectId, name, scenarioId)) throw conflict("NAME_CONFLICT", "场景名称已存在");
        int updated = scenarios.updateDetails(projectId, scenarioId, name, input.description(), input.variables(), input.settings(), input.revision(), actorId);
        if (updated != 1) throw conflict("REVISION_CONFLICT", "场景版本已变化");
        replaceSteps(projectId, scenarioId, input.steps());
        return get(projectId, scenarioId);
    }

    @Transactional
    public ScenarioRecord archive(UUID projectId, UUID scenarioId, Integer revision, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        ScenarioRecord current = scenarios.findActiveByIdForUpdate(projectId, scenarioId);
        if (current == null) throw notFound();
        if (revision == null || revision != current.revision()) throw conflict("REVISION_CONFLICT", "场景版本已变化");
        if (scenarios.archive(projectId, scenarioId, revision, actorId) != 1) throw conflict("REVISION_CONFLICT", "场景版本已变化");
        return get(projectId, scenarioId);
    }

    private void replaceSteps(UUID projectId, UUID scenarioId, List<ScenarioStepWrite> steps) {
        scenarios.deleteSteps(projectId, scenarioId);
        steps.stream().sorted(java.util.Comparator.comparing(ScenarioStepWrite::position))
                .forEach(step -> scenarios.insertStep(projectId, scenarioId, step));
    }

    private void validateReferences(UUID projectId, List<ScenarioStepWrite> steps) {
        for (ScenarioStepWrite step : steps) {
            if ("API_CASE".equalsIgnoreCase(step.kind())) {
                ApiCaseRecord apiCase = cases.findActiveByProjectId(projectId, step.apiCaseId());
                if (apiCase == null) throw invalidReference(step.apiCaseId());
            } else if ("SQL".equalsIgnoreCase(step.kind())) {
                UUID sourceId = UUID.fromString(step.stepConfig().path("dataSourceId").asText());
                JdbcDataSourceRecord source = dataSources.findById(projectId, sourceId);
                if (source == null || source.archived()) throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "INVALID_REFERENCE", "SQL 步骤引用的数据源不存在或已归档");
            } else if ("REDIS".equalsIgnoreCase(step.kind())) {
                UUID sourceId = UUID.fromString(step.stepConfig().path("dataSourceId").asText());
                RedisDataSourceRecord source = redisDataSources == null ? null : redisDataSources.findById(projectId, sourceId);
                if (source == null || source.archived()) throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "INVALID_REFERENCE", "Redis 步骤引用的数据源不存在或已归档");
            }
        }
    }

    private static ScenarioWrite requireRequest(ScenarioWrite request) {
        if (request == null) throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "INVALID_JSON", "场景请求不能为空");
        return request;
    }

    private ProjectRecord requireProject(UUID projectId) {
        ProjectRecord project = projects.findById(projectId);
        if (project == null) throw notFound();
        return project;
    }

    private ProjectRecord lockProject(UUID projectId) {
        ProjectRecord project = projects.findByIdForUpdate(projectId);
        if (project == null) throw notFound();
        return project;
    }

    private static void writable(ProjectRecord project) {
        if (project.archived()) throw conflict("PROJECT_ARCHIVED", "项目已归档，不能修改场景");
    }

    private static ApiDomainException invalidReference(UUID caseId) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "INVALID_REFERENCE", "接口用例不存在或已归档",
                Map.of("apiCaseId", String.valueOf(caseId)));
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }
}
