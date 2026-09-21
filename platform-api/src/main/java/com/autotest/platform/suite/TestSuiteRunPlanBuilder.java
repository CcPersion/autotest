package com.autotest.platform.suite;

import com.autotest.platform.api.ApiCaseRecord;
import com.autotest.platform.api.ApiCaseRepository;
import com.autotest.platform.api.ApiDefinitionRecord;
import com.autotest.platform.api.ApiDefinitionRepository;
import com.autotest.platform.environment.EnvironmentRecord;
import com.autotest.platform.scenario.ScenarioRecord;
import com.autotest.platform.scenario.ScenarioRepository;
import com.autotest.platform.scenario.ScenarioRunPlanBuilder;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Comparator;

@Component
public final class TestSuiteRunPlanBuilder {
    private final ApiCaseRepository cases;
    private final ApiDefinitionRepository definitions;
    private final ScenarioRepository scenarios;
    private final ScenarioRunPlanBuilder scenarioPlans;
    private final ObjectMapper json;

    public TestSuiteRunPlanBuilder(ApiCaseRepository cases, ApiDefinitionRepository definitions,
                                   ScenarioRepository scenarios, ScenarioRunPlanBuilder scenarioPlans,
                                   ObjectMapper json) {
        this.cases = cases;
        this.definitions = definitions;
        this.scenarios = scenarios;
        this.scenarioPlans = scenarioPlans;
        this.json = json;
    }

    public JsonNode build(TestSuiteRecord suite, EnvironmentRecord environment) {
        ObjectNode plan = json.createObjectNode();
        plan.put("jmeterVersion", "5.6.3");
        plan.put("targetType", "TEST_SUITE");
        plan.put("projectId", suite.projectId().toString());
        plan.put("suiteId", suite.id().toString());
        plan.put("environmentId", environment.id().toString());
        ArrayNode members = plan.putArray("suiteSteps");
        suite.members().stream().sorted(Comparator.comparingInt(TestSuiteMemberRecord::position)
                        .thenComparing(TestSuiteMemberRecord::id))
                .forEach(member -> appendMember(suite, environment, members, member));
        return plan;
    }

    private void appendMember(TestSuiteRecord suite, EnvironmentRecord environment, ArrayNode target,
                              TestSuiteMemberRecord member) {
        ObjectNode item = target.addObject();
        item.put("memberId", member.id().toString());
        item.put("position", member.position());
        item.put("targetType", member.targetType());
        item.put("targetId", member.targetId().toString());
        item.put("enabled", member.enabled());
        if ("API_CASE".equals(member.targetType())) {
            ApiCaseRecord apiCase = cases.findActiveByProjectId(suite.projectId(), member.targetId());
            if (apiCase == null) throw invalid("测试集合引用的接口用例不存在或已归档");
            item.put("targetName", apiCase.name());
            ApiDefinitionRecord definition = definitions.findById(suite.projectId(), apiCase.apiDefinitionId());
            if (definition == null || definition.archived()) throw invalid("测试集合引用的接口定义不存在或已归档");
            item.set("plan", scenarioPlans.buildApiCasePlan(suite.projectId(), environment, apiCase, definition,
                    "suite-" + suite.id() + "-" + member.id(), member.id().toString()));
        } else if ("SCENARIO".equals(member.targetType())) {
            ScenarioRecord scenario = scenarios.findById(suite.projectId(), member.targetId());
            if (scenario == null || scenario.archived()) throw invalid("测试集合引用的场景不存在或已归档");
            item.put("targetName", scenario.name());
            item.set("plan", scenarioPlans.build(scenarios.withSteps(scenario), environment));
        } else {
            throw invalid("测试集合成员类型不支持");
        }
    }

    private static ApiDomainException invalid(String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "TEST_SUITE_RUN_INVALID", message);
    }
}
