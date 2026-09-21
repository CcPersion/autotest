package com.autotest.platform.scenario;

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
public final class ScenarioRunService {
    private final ScenarioService scenarios;
    private final EnvironmentRepository environments;
    private final ScenarioRunPlanBuilder plans;
    private final RunService runs;

    public ScenarioRunService(ScenarioService scenarios, EnvironmentRepository environments,
                              ScenarioRunPlanBuilder plans, RunService runs) {
        this.scenarios = scenarios;
        this.environments = environments;
        this.plans = plans;
        this.runs = runs;
    }

    public RunRecord create(UUID projectId, UUID scenarioId, UUID environmentId,
                            String idempotencyKey, UUID actorId) {
        ScenarioRecord scenario = scenarios.get(projectId, scenarioId);
        if (scenario.archived()) throw conflict("SCENARIO_ARCHIVED", "场景已归档");
        EnvironmentRecord environment = environments.findById(projectId, environmentId);
        if (environment == null) throw notFound();
        if (environment.archived()) throw conflict("ENVIRONMENT_ARCHIVED", "环境已归档");
        JsonNode plan = plans.build(scenario, environment);
        return runs.create(projectId, environmentId, "SCENARIO", scenarioId, plan, idempotencyKey, actorId).run();
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }
}
