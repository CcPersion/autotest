package com.autotest.platform.scenario;

import com.autotest.platform.security.ApiDomainException;
import com.autotest.platform.api.ApiCaseRecord;
import com.autotest.platform.api.ApiCaseRepository;
import com.autotest.platform.api.ApiDefinitionRecord;
import com.autotest.platform.api.ApiDefinitionRepository;
import com.autotest.platform.environment.EnvironmentRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScenarioRunPlanBuilderTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void resolvesApiCaseReferenceIntoRunPlanWithoutChangingAsset() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        Instant now = Instant.now();
        ApiDefinitionRepository definitions = mock(ApiDefinitionRepository.class);
        ApiCaseRepository cases = mock(ApiCaseRepository.class);
        ApiDefinitionRecord definition = new ApiDefinitionRecord(definitionId, projectId, null, "订单详情", "GET",
                "/orders/{orderId}", json.readTree("""
                {"pathParams":[{"name":"orderId","value":"${orderId}"}],"query":[],
                 "headers":[],"cookies":[],"body":{"type":"NONE"}}
                """), 0, false, now, now);
        ApiCaseRecord apiCase = new ApiCaseRecord(caseId, projectId, definitionId, "成功详情", json.readTree("""
                {"pathParams":{"orderId":"1001"},"query":{},"headers":{},"cookies":{},"body":{"type":"NONE"},
                 "extractors":[]}
                """), json.readTree("{\"orderId\":1001}"), json.readTree("[]"), 0, false, now, now);
        when(cases.findActiveByProjectId(projectId, caseId)).thenReturn(apiCase);
        when(definitions.findById(projectId, definitionId)).thenReturn(definition);
        ScenarioStepRecord apiStep = new ScenarioStepRecord(stepId, scenarioId, null, 0, "API_CASE", "详情",
                true, "MAIN", "REFERENCE", caseId, "STOP", json.readTree("{}"));
        ScenarioStepRecord waitStep = new ScenarioStepRecord(UUID.randomUUID(), scenarioId, null, 1, "WAIT", "等待",
                true, "MAIN", null, null, "STOP", json.readTree("{\"waitMillis\":5}"));
        ScenarioRecord scenario = new ScenarioRecord(scenarioId, projectId, "订单链路", "", json.readTree("{}"),
                json.readTree("{}"), 0, false, now, now, List.of(apiStep, waitStep));
        EnvironmentRecord environment = new EnvironmentRecord(UUID.randomUUID(), projectId, "测试", "http://test.local",
                json.readTree("{\"tenant\":\"demo\"}"), json.readTree("{}"), 0, false, now, now);

        var plan = new ScenarioRunPlanBuilder(definitions, cases, json).build(scenario, environment);

        assertEquals("API_CASE", plan.path("scenarioSteps").get(0).path("kind").asText());
        assertEquals("/orders/1001", plan.path("scenarioSteps").get(0).path("plan").path("urlTemplate").asText());
        assertEquals(1001, plan.path("scenarioSteps").get(0).path("plan").path("variableScopes")
                .path("caseVariables").path("orderId").asInt());
        assertEquals(5, plan.path("scenarioSteps").get(1).path("waitMillis").asInt());
        assertTrue(apiCase.caseSpec().path("pathParams").path("orderId").asText().equals("1001"));
    }

    @Test
    void enrichesCustomHttpPlanWithEnvironmentAndScenarioScopes() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        Instant now = Instant.now();
        ScenarioStepRecord httpStep = new ScenarioStepRecord(UUID.randomUUID(), scenarioId, null, 0, "HTTP", "健康检查",
                true, "MAIN", null, null, "STOP", json.readTree("""
                {"plan":{"method":"GET","urlTemplate":"/actuator/health","headers":[],"body":{"type":"NONE"}}}
                """));
        ScenarioRecord scenario = new ScenarioRecord(scenarioId, projectId, "健康检查", "",
                json.readTree("{\"tenant\":\"demo\"}"), json.readTree("{}"), 0, false, now, now, List.of(httpStep));
        EnvironmentRecord environment = new EnvironmentRecord(UUID.randomUUID(), projectId, "测试", "http://platform-api:8080",
                json.readTree("{\"region\":\"cn\"}"), json.readTree("{}"), 0, false, now, now);

        var plan = new ScenarioRunPlanBuilder(mock(ApiDefinitionRepository.class), mock(ApiCaseRepository.class), json)
                .build(scenario, environment);
        var custom = plan.path("scenarioSteps").get(0).path("plan");

        assertEquals("http://platform-api:8080", custom.path("baseUrl").asText());
        assertEquals("GET", custom.path("method").asText());
        assertEquals("demo", custom.path("variableScopes").path("scenario").path("tenant").asText());
        assertEquals("cn", custom.path("variableScopes").path("environment").path("region").asText());
    }

    @Test
    void preservesControlFlowParentAndBranchInRunPlan() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        UUID conditionId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        Instant now = Instant.now();
        ScenarioStepRecord condition = new ScenarioStepRecord(conditionId, scenarioId, null, 0, "CONDITION", "状态判断",
                true, "MAIN", null, null, "STOP", json.readTree("{\"left\":\"${status}\",\"operator\":\"EQUALS\",\"right\":\"READY\"}"));
        ScenarioStepRecord child = new ScenarioStepRecord(childId, scenarioId, conditionId, 1, "WAIT", "满足后等待",
                true, "MAIN", null, null, "STOP", json.readTree("{\"waitMillis\":1,\"branch\":\"ELSE\"}"));
        ScenarioRecord scenario = new ScenarioRecord(scenarioId, projectId, "控制流", "", json.readTree("{}"),
                json.readTree("{}"), 0, false, now, now, List.of(condition, child));
        EnvironmentRecord environment = new EnvironmentRecord(UUID.randomUUID(), projectId, "测试", "http://test.local",
                json.readTree("{}"), json.readTree("{}"), 0, false, now, now);

        var plan = new ScenarioRunPlanBuilder(mock(ApiDefinitionRepository.class), mock(ApiCaseRepository.class), json)
                .build(scenario, environment);

        assertEquals(conditionId.toString(), plan.path("scenarioSteps").get(1).path("parentId").asText());
        assertEquals("ELSE", plan.path("scenarioSteps").get(1).path("branch").asText());
        assertEquals("CONDITION", plan.path("scenarioSteps").get(0).path("plan").path("planKind").asText());
    }

    @Test
    void rejectsMultipleEnabledApiCaseRowsBeforeScenarioExecutionStarts() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        Instant now = Instant.now();
        ApiDefinitionRepository definitions = mock(ApiDefinitionRepository.class);
        ApiCaseRepository cases = mock(ApiCaseRepository.class);
        ApiDefinitionRecord definition = new ApiDefinitionRecord(definitionId, projectId, null, "订单", "GET",
                "/orders/{id}", json.readTree("{\"pathParams\":[{\"name\":\"id\",\"value\":\"${id}\"}],\"query\":[],\"headers\":[],\"cookies\":[],\"body\":{\"type\":\"NONE\"}}"), 0, false, now, now);
        ApiCaseRecord apiCase = new ApiCaseRecord(caseId, projectId, definitionId, "多行", json.readTree("""
                {"pathParams":{"id":"1"},"query":{},"headers":{},"cookies":{},"body":{"type":"NONE"},
                 "dataRows":[{"id":"r1","enabled":true,"values":{"id":1}},
                              {"id":"r2","enabled":true,"values":{"id":2}}],"extractors":[]}
                """), json.readTree("{}"), json.readTree("[]"), 0, false, now, now);
        when(cases.findActiveByProjectId(projectId, caseId)).thenReturn(apiCase);
        when(definitions.findById(projectId, definitionId)).thenReturn(definition);
        ScenarioStepRecord step = new ScenarioStepRecord(UUID.randomUUID(), scenarioId, null, 0, "API_CASE", "多行",
                true, "MAIN", "REFERENCE", caseId, "STOP", json.readTree("{}"));
        ScenarioRecord scenario = new ScenarioRecord(scenarioId, projectId, "多行场景", "", json.readTree("{}"),
                json.readTree("{}"), 0, false, now, now, List.of(step));
        EnvironmentRecord environment = new EnvironmentRecord(UUID.randomUUID(), projectId, "测试", "http://test.local",
                json.readTree("{}"), json.readTree("{}"), 0, false, now, now);

        ApiDomainException error = assertThrows(ApiDomainException.class,
                () -> new ScenarioRunPlanBuilder(definitions, cases, json).build(scenario, environment));

        assertTrue(error.getMessage().contains("DATA_ROWS_IN_SCENARIO_UNSUPPORTED"));
    }
}
