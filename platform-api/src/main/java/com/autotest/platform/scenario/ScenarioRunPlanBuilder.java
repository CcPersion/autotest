package com.autotest.platform.scenario;

import com.autotest.platform.api.ApiCaseRecord;
import com.autotest.platform.api.ApiCaseRepository;
import com.autotest.platform.api.ApiDefinitionRecord;
import com.autotest.platform.api.ApiDefinitionRepository;
import com.autotest.platform.environment.EnvironmentRecord;
import com.autotest.platform.environment.JdbcDataSourceRecord;
import com.autotest.platform.environment.JdbcDataSourceRepository;
import com.autotest.platform.security.ApiDomainException;
import com.autotest.platform.environment.RedisDataSourceRecord;
import com.autotest.platform.environment.RedisDataSourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** 将已保存场景解析成一次运行专用的结构化计划；不修改用户资产。 */
@Component
public final class ScenarioRunPlanBuilder {
    private final ApiDefinitionRepository definitions;
    private final ApiCaseRepository cases;
    private final ObjectMapper json;
    private final JdbcDataSourceRepository dataSources;
    private final RedisDataSourceRepository redisDataSources;

    public ScenarioRunPlanBuilder(ApiDefinitionRepository definitions, ApiCaseRepository cases,
                                  ObjectMapper json) {
        this(definitions, cases, json, null);
    }

    public ScenarioRunPlanBuilder(ApiDefinitionRepository definitions, ApiCaseRepository cases,
                                  ObjectMapper json, JdbcDataSourceRepository dataSources) {
        this(definitions, cases, json, dataSources, null);
    }

    @Autowired
    public ScenarioRunPlanBuilder(ApiDefinitionRepository definitions, ApiCaseRepository cases,
                                  ObjectMapper json, JdbcDataSourceRepository dataSources,
                                  RedisDataSourceRepository redisDataSources) {
        this.definitions = definitions;
        this.cases = cases;
        this.json = json;
        this.dataSources = dataSources;
        this.redisDataSources = redisDataSources;
    }

    public JsonNode build(ScenarioRecord scenario, EnvironmentRecord environment) {
        ObjectNode plan = json.createObjectNode();
        plan.put("jmeterVersion", "5.6.3");
        plan.put("projectId", scenario.projectId().toString());
        plan.put("planId", "scenario-" + scenario.id());
        plan.put("scenarioId", scenario.id().toString());
        plan.set("scenarioVariables", scenario.variables() == null ? json.createObjectNode() : scenario.variables().deepCopy());
        ArrayNode steps = plan.putArray("scenarioSteps");
        for (ScenarioStepRecord step : scenario.steps()) {
            ObjectNode target = steps.addObject();
            target.put("stepId", step.id().toString());
            target.put("kind", step.kind());
            target.put("enabled", step.enabled());
            target.put("section", step.section());
            target.put("failureStrategy", step.failureStrategy());
            if (step.stepConfig() != null && step.stepConfig().path("retry").isObject()) {
                target.set("retry", step.stepConfig().path("retry").deepCopy());
            }
            target.put("position", step.position());
            if (step.parentId() != null) target.put("parentId", step.parentId().toString());
            String defaultBranch = "THEN";
            if (step.parentId() != null) {
                for (ScenarioStepRecord candidate : scenario.steps()) {
                    if (step.parentId().equals(candidate.id())) {
                        defaultBranch = "LOOP".equals(candidate.kind()) ? "BODY" : "THEN";
                        break;
                    }
                }
            }
            target.put("branch", step.stepConfig() != null && step.stepConfig().has("branch")
                    ? step.stepConfig().path("branch").asText(defaultBranch) : defaultBranch);
            if ("API_CASE".equals(step.kind())) {
                if (step.apiCaseId() == null) throw invalid("场景引用接口用例不能为空");
                ApiCaseRecord apiCase = cases.findActiveByProjectId(scenario.projectId(), step.apiCaseId());
                if (apiCase == null) throw invalid("场景引用的接口用例不存在或已归档");
                ApiDefinitionRecord definition = definitions.findById(scenario.projectId(), apiCase.apiDefinitionId());
                if (definition == null || definition.archived()) throw invalid("场景引用的接口定义不存在或已归档");
                target.set("plan", apiCasePlan(scenario.projectId(), environment, step.id().toString(),
                        "scenario-" + scenario.id() + "-" + step.id(), scenario.variables(), definition, apiCase));
            } else if ("HTTP".equals(step.kind())) {
                JsonNode custom = step.stepConfig() == null ? null : step.stepConfig().get("plan");
                if (custom == null || !custom.isObject()) throw invalid("自定义 HTTP 步骤缺少 plan 配置");
                target.set("plan", customHttpPlan(scenario, environment, step, custom));
            } else if ("WAIT".equals(step.kind())) {
                long waitMillis = step.stepConfig() == null ? 0 : step.stepConfig().path("waitMillis").asLong(-1);
                if (waitMillis < 0) throw invalid("等待步骤缺少非负 waitMillis");
                target.put("waitMillis", waitMillis);
            } else if ("SQL".equals(step.kind())) {
                target.set("plan", sqlPlan(scenario, environment, step));
            } else if ("REDIS".equals(step.kind())) {
                target.set("plan", redisPlan(scenario, environment, step));
            } else if ("CONDITION".equals(step.kind()) || "LOOP".equals(step.kind())) {
                target.set("plan", controlFlowPlan(scenario, environment, step));
            } else {
                throw invalid("当前运行切片暂不支持步骤类型: " + step.kind());
            }
        }
        return plan;
    }

    /** 为测试集合中的单个接口用例生成与场景引用一致的执行计划。 */
    public ObjectNode buildApiCasePlan(UUID projectId, EnvironmentRecord environment, ApiCaseRecord apiCase,
                                       ApiDefinitionRecord definition, String planId, String stepId) {
        return apiCasePlan(projectId, environment, stepId, planId, json.createObjectNode(), definition, apiCase);
    }

    private ObjectNode sqlPlan(ScenarioRecord scenario, EnvironmentRecord environment, ScenarioStepRecord step) {
        JsonNode config = step.stepConfig();
        SqlStepValidator.validate(config, "stepConfig");
        if (dataSources == null) throw invalid("SQL 数据源仓储未配置");
        JdbcDataSourceRecord source;
        try {
            source = dataSources.findById(scenario.projectId(), UUID.fromString(config.path("dataSourceId").asText()));
        } catch (IllegalArgumentException e) {
            throw invalid("SQL 步骤数据源无效");
        }
        if (source == null || source.archived() || !source.environmentId().equals(environment.id())) throw invalid("SQL 步骤数据源不属于当前环境");
        ObjectNode plan = json.createObjectNode();
        plan.put("planKind", "JDBC_SQL");
        plan.put("planId", "scenario-" + scenario.id() + "-" + step.id());
        plan.put("stepId", step.id().toString());
        plan.put("databaseType", source.databaseType());
        plan.put("host", source.host());
        plan.put("port", source.port());
        plan.put("databaseName", source.databaseName());
        plan.put("username", source.username());
        plan.put("credentialRef", "${secret:" + source.secretRef() + "}");
        plan.put("sql", config.path("sql").asText());
        plan.set("parameters", config.has("parameters") ? config.get("parameters").deepCopy() : json.createObjectNode());
        plan.set("extractors", config.has("extractors") ? config.get("extractors").deepCopy() : json.createArrayNode());
        plan.set("assertions", config.has("assertions") ? config.get("assertions").deepCopy() : json.createArrayNode());
        plan.set("variableScopes", variableScopes(scenario, environment));
        plan.put("allowWrite", config.path("allowWrite").asBoolean(false));
        plan.put("confirmed", config.path("confirmed").asBoolean(false));
        return plan;
    }

    private ObjectNode redisPlan(ScenarioRecord scenario, EnvironmentRecord environment, ScenarioStepRecord step) {
        JsonNode config = step.stepConfig();
        RedisStepValidator.validate(config, "stepConfig");
        if (redisDataSources == null) throw invalid("Redis 数据源仓储未配置");
        RedisDataSourceRecord source;
        try {
            source = redisDataSources.findById(scenario.projectId(), UUID.fromString(config.path("dataSourceId").asText()));
        } catch (IllegalArgumentException e) {
            throw invalid("Redis 步骤数据源无效");
        }
        if (source == null || source.archived() || !source.environmentId().equals(environment.id())) throw invalid("Redis 步骤数据源不属于当前环境");
        ObjectNode plan = json.createObjectNode();
        plan.put("planKind", "REDIS");
        plan.put("planId", "scenario-" + scenario.id() + "-" + step.id());
        plan.put("stepId", step.id().toString());
        plan.put("host", source.host());
        plan.put("port", source.port());
        plan.put("databaseNumber", source.databaseNumber());
        if (source.username() != null) plan.put("username", source.username());
        if (source.secretRef() != null) plan.put("credentialRef", "${secret:" + source.secretRef() + "}");
        plan.set("options", source.options() == null ? json.createObjectNode() : source.options().deepCopy());
        for (String field : new String[]{"command", "key", "value", "allowWrite", "confirmed", "extractors", "assertions"}) {
            if (config.has(field)) plan.set(field, config.get(field).deepCopy());
        }
        plan.set("variableScopes", variableScopes(scenario, environment));
        return plan;
    }

    private ObjectNode variableScopes(ScenarioRecord scenario, EnvironmentRecord environment) {
        ObjectNode scopes = json.createObjectNode();
        scopes.set("environment", environment.variables().deepCopy());
        scopes.set("scenario", scenario.variables().deepCopy());
        scopes.putObject("extracted");
        return scopes;
    }

    private ObjectNode controlFlowPlan(ScenarioRecord scenario, EnvironmentRecord environment,
                                       ScenarioStepRecord step) {
        JsonNode config = step.stepConfig();
        if (config == null || !config.isObject()) throw invalid("控制流步骤配置必须是对象");
        ObjectNode plan = config.deepCopy();
        plan.put("planKind", step.kind());
        plan.put("planId", "scenario-" + scenario.id() + "-" + step.id());
        plan.put("stepId", step.id().toString());
        plan.set("variableScopes", variableScopes(scenario, environment));
        return plan;
    }

    private ObjectNode customHttpPlan(ScenarioRecord scenario, EnvironmentRecord environment,
                                      ScenarioStepRecord step, JsonNode source) {
        ObjectNode plan = source.deepCopy();
        plan.put("planId", plan.path("planId").asText("scenario-" + scenario.id() + "-" + step.id()));
        plan.put("stepId", step.id().toString());
        if (plan.path("baseUrl").asText("").isBlank()) plan.put("baseUrl", environment.baseUrl());
        ObjectNode scopes;
        JsonNode existingScopes = plan.get("variableScopes");
        if (existingScopes == null || existingScopes.isNull()) {
            scopes = plan.putObject("variableScopes");
        } else if (existingScopes.isObject()) {
            scopes = (ObjectNode) existingScopes;
        } else {
            throw invalid("自定义 HTTP variableScopes 必须是对象");
        }
        if (!scopes.has("environment")) scopes.set("environment", environment.variables().deepCopy());
        if (!scopes.has("scenario")) scopes.set("scenario", scenario.variables().deepCopy());
        if (!scopes.has("caseVariables")) scopes.putObject("caseVariables");
        if (!scopes.has("dataRow")) scopes.putObject("dataRow");
        if (!scopes.has("extracted")) scopes.putObject("extracted");
        if (!plan.has("variables")) plan.putObject("variables");
        if (!plan.has("body")) plan.putObject("body").put("type", "NONE");
        return plan;
    }

    private ObjectNode apiCasePlan(UUID projectId, EnvironmentRecord environment, String stepId, String planId,
                                   JsonNode scenarioVariables, ApiDefinitionRecord definition,
                                   ApiCaseRecord apiCase) {
        ObjectNode plan = json.createObjectNode();
        plan.put("jmeterVersion", "5.6.3");
        plan.put("projectId", projectId.toString());
        plan.put("planId", planId);
        plan.put("stepId", stepId);
        plan.put("baseUrl", environment.baseUrl());
        plan.put("method", definition.method());
        JsonNode caseSpec = apiCase.caseSpec();
        plan.put("urlTemplate", resolvedUrl(definition, caseSpec));
        plan.set("query", mergedParameters(definition.requestSpec().path("query"), caseSpec.path("query")));
        plan.set("headers", mergedHeaders(environment.requestOptions(), definition.requestSpec().path("headers"),
                caseSpec.path("headers")));
        plan.set("cookies", mergedParameters(definition.requestSpec().path("cookies"), caseSpec.path("cookies")));
        plan.set("body", caseSpec.path("body").deepCopy());
        ObjectNode options = mergedOptions(environment.requestOptions(), definition.requestSpec().path("options"));
        plan.set("options", options.deepCopy());
        for (String field : new String[]{"followRedirects", "connectTimeoutMillis", "responseTimeoutMillis",
                "proxy", "clientCertificate"}) {
            if (options.has(field)) plan.set(field, options.get(field).deepCopy());
        }
        plan.set("variables", apiCase.variables().deepCopy());
        ObjectNode scopes = plan.putObject("variableScopes");
        scopes.set("environment", environment.variables().deepCopy());
        scopes.set("scenario", scenarioVariables == null ? json.createObjectNode() : scenarioVariables.deepCopy());
        scopes.set("caseVariables", apiCase.variables().deepCopy());
        scopes.putObject("dataRow");
        scopes.putObject("extracted");
        plan.set("extractors", caseSpec.path("extractors").deepCopy());
        plan.set("assertions", apiCase.assertions().deepCopy());
        if (caseSpec.has("dataRows")) {
            JsonNode dataRows = caseSpec.get("dataRows");
            long enabledRows = dataRows != null && dataRows.isArray()
                    ? java.util.stream.StreamSupport.stream(dataRows.spliterator(), false)
                    .filter(row -> row != null && row.path("enabled").asBoolean(true)).count() : 0;
            if (enabledRows > 1) {
                throw invalid("DATA_ROWS_IN_SCENARIO_UNSUPPORTED: 场景引用接口用例不能使用多条启用数据行");
            }
            plan.set("dataRows", dataRows.deepCopy());
        }
        if (caseSpec.has("dataRowOptions")) plan.set("dataRowOptions", caseSpec.get("dataRowOptions").deepCopy());
        return plan;
    }

    private String resolvedUrl(ApiDefinitionRecord definition, JsonNode caseSpec) {
        String url = definition.urlTemplate();
        JsonNode overrides = caseSpec.path("pathParams");
        for (JsonNode item : definition.requestSpec().path("pathParams")) {
            String name = item.path("name").asText();
            JsonNode value = overrides.get(name);
            if (value == null) value = item.get("value");
            url = url.replace("{" + name + "}", value == null ? "" : value.asText());
        }
        return url;
    }

    private ArrayNode mergedParameters(JsonNode declared, JsonNode overrides) {
        ArrayNode result = json.createArrayNode();
        for (JsonNode item : declared) {
            ObjectNode copy = item.deepCopy();
            String name = item.path("name").asText();
            JsonNode override = overrides.get(name);
            if (override == null && ("headers".equals(name) || "cookies".equals(name))) {
                override = findIgnoreCase(overrides, name);
            }
            if (override != null) copy.set("value", override.deepCopy());
            result.add(copy);
        }
        return result;
    }

    private ArrayNode mergedHeaders(JsonNode environmentOptions, JsonNode declared, JsonNode overrides) {
        ArrayNode result = json.createArrayNode();
        JsonNode defaults = environmentOptions == null ? null : environmentOptions.get("defaultHeaders");
        if (defaults != null && defaults.isArray()) {
            for (JsonNode item : defaults) result.add(item.deepCopy());
        }
        ArrayNode declaredValues = mergedParameters(declared, overrides);
        for (JsonNode item : declaredValues) {
            String name = item.path("name").asText();
            for (int index = result.size() - 1; index >= 0; index--) {
                if (result.get(index).path("name").asText().equalsIgnoreCase(name)) result.remove(index);
            }
            result.add(item);
        }
        return result;
    }

    private JsonNode findIgnoreCase(JsonNode object, String name) {
        if (!object.isObject()) return null;
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    private ObjectNode mergedOptions(JsonNode environment, JsonNode definition) {
        ObjectNode result = environment != null && environment.isObject()
                ? environment.deepCopy() : json.createObjectNode();
        if (definition != null && definition.isObject()) {
            definition.fields().forEachRemaining(entry -> result.set(entry.getKey(), entry.getValue().deepCopy()));
        }
        return result;
    }

    private static ApiDomainException invalid(String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "SCENARIO_RUN_INVALID", message);
    }
}
