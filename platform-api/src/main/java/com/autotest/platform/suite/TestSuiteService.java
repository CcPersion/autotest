package com.autotest.platform.suite;

import com.autotest.platform.api.ApiCaseRepository;
import com.autotest.platform.environment.EnvironmentRecord;
import com.autotest.platform.environment.EnvironmentRepository;
import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.scenario.ScenarioRecord;
import com.autotest.platform.scenario.ScenarioRepository;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TestSuiteService {
    private final TestSuiteRepository suites;
    private final ProjectRepository projects;
    private final ApiCaseRepository cases;
    private final ScenarioRepository scenarios;
    private final EnvironmentRepository environments;

    public TestSuiteService(TestSuiteRepository suites, ProjectRepository projects, ApiCaseRepository cases,
                            ScenarioRepository scenarios) {
        this(suites, projects, cases, scenarios, null);
    }

    @Autowired
    public TestSuiteService(TestSuiteRepository suites, ProjectRepository projects, ApiCaseRepository cases,
                            ScenarioRepository scenarios, EnvironmentRepository environments) {
        this.suites = suites;
        this.projects = projects;
        this.cases = cases;
        this.scenarios = scenarios;
        this.environments = environments;
    }

    public List<TestSuiteRecord> list(UUID projectId, boolean includeArchived) {
        requireProject(projectId);
        return suites.findAll(projectId, includeArchived);
    }

    public TestSuiteRecord get(UUID projectId, UUID suiteId) {
        requireProject(projectId);
        TestSuiteRecord suite = suites.findById(projectId, suiteId);
        if (suite == null) throw notFound();
        return suite;
    }

    @Transactional
    public TestSuiteRecord create(UUID projectId, TestSuiteWrite request, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        TestSuiteWrite input = validateRequest(projectId, request);
        if (suites.existsActiveName(projectId, input.name().strip())) throw conflict("NAME_CONFLICT", "测试集合名称已存在");
        try {
            return suites.insert(projectId, input.name().strip(), input.description(), input.environmentId(),
                    input.members(), actorId);
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "测试集合名称已存在");
        }
    }

    @Transactional
    public TestSuiteRecord update(UUID projectId, UUID suiteId, TestSuiteWrite request, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        TestSuiteRecord current = suites.findActiveByIdForUpdate(projectId, suiteId);
        if (current == null) throw notFound();
        TestSuiteWrite input = validateRequest(projectId, request);
        if (input.revision() == null || input.revision() != current.revision()) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT", "测试集合版本已变化",
                    Map.of("currentRevision", current.revision(), "requestedRevision", input.revision() == null ? -1 : input.revision()));
        }
        if (suites.existsActiveNameExcluding(projectId, input.name().strip(), suiteId)) {
            throw conflict("NAME_CONFLICT", "测试集合名称已存在");
        }
        if (suites.updateDetails(projectId, suiteId, input.name().strip(), input.description(), input.environmentId(),
                input.members(), input.revision(), actorId) != 1) throw conflict("REVISION_CONFLICT", "测试集合版本已变化");
        return get(projectId, suiteId);
    }

    @Transactional
    public TestSuiteRecord archive(UUID projectId, UUID suiteId, Integer revision, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        TestSuiteRecord current = suites.findActiveByIdForUpdate(projectId, suiteId);
        if (current == null) throw notFound();
        if (revision == null || revision != current.revision()) throw conflict("REVISION_CONFLICT", "测试集合版本已变化");
        if (suites.archive(projectId, suiteId, revision, actorId) != 1) throw conflict("REVISION_CONFLICT", "测试集合版本已变化");
        return get(projectId, suiteId);
    }

    private TestSuiteWrite validateRequest(UUID projectId, TestSuiteWrite request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw invalid("测试集合名称不能为空");
        }
        if (request.name().strip().length() > 256) throw invalid("测试集合名称过长");
        if (request.environmentId() != null && environments != null) {
            EnvironmentRecord environment = environments.findById(projectId, request.environmentId());
            if (environment == null || environment.archived()) throw invalid("测试集合环境不存在或已归档");
        }
        List<TestSuiteWrite.MemberWrite> members = request.members() == null ? List.of() : request.members();
        Set<Integer> positions = new HashSet<>();
        for (TestSuiteWrite.MemberWrite member : members) {
            if (member == null || member.id() == null || member.targetId() == null || member.position() < 0) {
                throw invalid("测试集合成员不完整");
            }
            if (!positions.add(member.position())) throw invalid("测试集合成员顺序不能重复");
            String type = member.targetType() == null ? "" : member.targetType().toUpperCase(Locale.ROOT);
            if (!type.equals("API_CASE") && !type.equals("SCENARIO")) throw invalid("测试集合成员类型不支持");
            if (type.equals("API_CASE")) {
                if (cases.findActiveByProjectId(projectId, member.targetId()) == null) throw invalid("接口用例不存在或已归档");
            } else {
                ScenarioRecord scenario = scenarios.findById(projectId, member.targetId());
                if (scenario == null || scenario.archived()) throw invalid("场景不存在或已归档");
            }
        }
        List<TestSuiteWrite.MemberWrite> normalized = members.stream()
                .map(member -> new TestSuiteWrite.MemberWrite(member.id(), member.targetType().toUpperCase(Locale.ROOT),
                        member.targetId(), member.position(), member.enabled()))
                .sorted(java.util.Comparator.comparingInt(TestSuiteWrite.MemberWrite::position)
                        .thenComparing(TestSuiteWrite.MemberWrite::id))
                .toList();
        return new TestSuiteWrite(request.name().strip(), request.description(), request.environmentId(), normalized,
                request.revision());
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
        if (project.archived()) throw conflict("PROJECT_ARCHIVED", "项目已归档，不能修改测试集合");
    }

    private static ApiDomainException invalid(String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "INVALID_TEST_SUITE", message);
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }
}
