package com.autotest.platform.suite;

import com.autotest.platform.api.ApiCaseRecord;
import com.autotest.platform.api.ApiCaseRepository;
import com.autotest.platform.api.ApiDefinitionRecord;
import com.autotest.platform.api.ApiDefinitionRepository;
import com.autotest.platform.environment.EnvironmentRecord;
import com.autotest.platform.scenario.ScenarioRecord;
import com.autotest.platform.scenario.ScenarioRepository;
import com.autotest.platform.scenario.ScenarioRunPlanBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSuiteRunPlanBuilderTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void resolvesEnabledMembersInPositionOrder() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        Instant now = Instant.now();
        EnvironmentRecord environment = new EnvironmentRecord(environmentId, projectId, "测试", "http://test.local",
                json.readTree("{}"), json.readTree("{}"), 0, false, now, now);
        ApiCaseRepository cases = mock(ApiCaseRepository.class);
        ApiDefinitionRepository definitions = mock(ApiDefinitionRepository.class);
        ScenarioRepository scenarios = mock(ScenarioRepository.class);
        ScenarioRunPlanBuilder scenariosPlan = mock(ScenarioRunPlanBuilder.class);
        ApiCaseRecord apiCase = new ApiCaseRecord(caseId, projectId, definitionId, "登录", json.readTree("{}"),
                json.readTree("{}"), json.readTree("[]"), 0, false, now, now);
        ApiDefinitionRecord definition = new ApiDefinitionRecord(definitionId, projectId, null, "登录", "POST",
                "/login", json.readTree("{\"headers\":[],\"query\":[],\"cookies\":[]}"), 0, false, now, now);
        when(cases.findActiveByProjectId(projectId, caseId)).thenReturn(apiCase);
        when(definitions.findById(projectId, definitionId)).thenReturn(definition);
        ScenarioRecord scenario = new ScenarioRecord(scenarioId, projectId, "业务", "", json.readTree("{}"),
                json.readTree("{}"), 0, false, now, now, List.of());
        when(scenarios.findById(projectId, scenarioId)).thenReturn(scenario);
        when(scenarios.withSteps(scenario)).thenReturn(scenario);
        when(scenariosPlan.build(scenario, environment)).thenReturn(json.readTree("{\"scenarioSteps\":[]}"));
        when(scenariosPlan.buildApiCasePlan(any(), any(), any(), any(), any(), any()))
                .thenReturn((ObjectNode) json.readTree("{\"method\":\"POST\"}"));

        TestSuiteRecord suite = new TestSuiteRecord(suiteId, projectId, "回归", "", environmentId, 0, false,
                now, now, List.of(
                new TestSuiteMemberRecord(UUID.randomUUID(), suiteId, projectId, 1, "SCENARIO", scenarioId, true),
                new TestSuiteMemberRecord(UUID.randomUUID(), suiteId, projectId, 0, "API_CASE", caseId, true)));

        var plan = new TestSuiteRunPlanBuilder(cases, definitions, scenarios, scenariosPlan, json)
                .build(suite, environment);

        assertEquals("TEST_SUITE", plan.path("targetType").asText());
        assertEquals("API_CASE", plan.path("suiteSteps").get(0).path("targetType").asText());
        assertEquals("登录", plan.path("suiteSteps").get(0).path("targetName").asText());
        assertEquals("SCENARIO", plan.path("suiteSteps").get(1).path("targetType").asText());
        assertEquals("业务", plan.path("suiteSteps").get(1).path("targetName").asText());
        assertEquals(2, plan.path("suiteSteps").size());
    }
}
