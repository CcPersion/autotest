package com.autotest.platform.suite;

import com.autotest.platform.environment.EnvironmentRecord;
import com.autotest.platform.environment.EnvironmentRepository;
import com.autotest.platform.run.RunRecord;
import com.autotest.platform.run.RunService;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public final class TestSuiteRunService {
    private final TestSuiteService suites;
    private final EnvironmentRepository environments;
    private final TestSuiteRunPlanBuilder plans;
    private final RunService runs;

    public TestSuiteRunService(TestSuiteService suites, EnvironmentRepository environments,
                               TestSuiteRunPlanBuilder plans, RunService runs) {
        this.suites = suites;
        this.environments = environments;
        this.plans = plans;
        this.runs = runs;
    }

    public RunRecord create(UUID projectId, UUID suiteId, UUID requestedEnvironmentId,
                            String idempotencyKey, UUID actorId) {
        return createResult(projectId, suiteId, requestedEnvironmentId, idempotencyKey, actorId).run();
    }

    public RunService.CreateResult createResult(UUID projectId, UUID suiteId, UUID requestedEnvironmentId,
                                                String idempotencyKey, UUID actorId) {
        TestSuiteRecord suite = suites.get(projectId, suiteId);
        if (suite.archived()) throw conflict("TEST_SUITE_ARCHIVED", "测试集合已归档");
        UUID environmentId = requestedEnvironmentId == null ? suite.environmentId() : requestedEnvironmentId;
        if (environmentId == null) throw invalid("运行测试集合必须选择环境");
        EnvironmentRecord environment = environments.findById(projectId, environmentId);
        if (environment == null || environment.archived()) throw notFound();
        JsonNode plan = plans.build(suite, environment);
        return runs.create(projectId, environmentId, "TEST_SUITE", suiteId, plan, idempotencyKey, actorId);
    }

    private static ApiDomainException invalid(String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "TEST_SUITE_RUN_INVALID", message);
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }
}
