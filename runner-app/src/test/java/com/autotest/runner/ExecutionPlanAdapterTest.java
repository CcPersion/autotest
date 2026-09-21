package com.autotest.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPlanAdapterTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void mapsStoredExecutionPlanToJmeterPlanWithoutDroppingTypedValues() throws Exception {
        JmeterPlan plan = ExecutionPlanAdapter.fromJson(json.readTree("""
                {
                  "planId":"run-plan",
                  "stepId":"case-step",
                  "baseUrl":"http://127.0.0.1:18081",
                  "method":"POST",
                  "urlTemplate":"/orders/${orderId}",
                  "query":[{"name":"verbose","value":"true","enabled":true}],
                  "headers":[{"name":"X-Trace","value":"${traceId}","enabled":true}],
                  "body":{"type":"JSON","value":{"amount":12,"ok":true}},
                  "variables":{"orderId":"42","traceId":"trace-1","attempt":2,"enabled":true},
                  "assertions":[{"type":"STATUS","operator":"EQUALS","expected":201},
                    {"type":"JSON_PATH","operator":"EQUALS","expression":"$.ok","expected":true}]
                }
                """));

        assertEquals("run-plan", plan.planId());
        assertEquals("POST", plan.method());
        assertEquals("42", plan.variables().get("orderId"));
        assertEquals("2", plan.variables().get("attempt"));
        assertEquals("true", plan.variables().get("enabled"));
        assertEquals(1, plan.query().size());
        assertEquals(2, plan.assertions().size());
        assertTrue(plan.body().value().get("ok").asBoolean());
    }

    @Test
    void stringifiesObjectArrayAndScalarVariablesForTextParameters() throws Exception {
        JmeterPlan plan = ExecutionPlanAdapter.fromJson(json.readTree("""
                {
                  "planId":"typed-text-fields",
                  "baseUrl":"http://127.0.0.1",
                  "method":"GET",
                  "urlTemplate":"/values",
                  "variables":{"object":{"a":1,"ok":true},"array":[1,true],"number":7,"flag":true},
                  "query":[
                    {"name":"object","value":"${object}"},
                    {"name":"array","value":"${array}"},
                    {"name":"number","value":"${number}"},
                    {"name":"flag","value":"${flag}"}
                  ],
                  "cookies":[{"name":"session","value":"${secret:session}","domain":null,"path":null}],
                  "body":{"type":"NONE"}
                }
                """));

        assertEquals("{\"a\":1,\"ok\":true}", plan.query().get(0).value());
        assertEquals("[1,true]", plan.query().get(1).value());
        assertEquals("7", plan.query().get(2).value());
        assertEquals("true", plan.query().get(3).value());
        assertEquals("", plan.cookies().get(0).domain());
        assertEquals("/", plan.cookies().get(0).path());
    }

    @Test
    void readsTestSuiteMembersInStablePositionOrder() throws Exception {
        List<SuiteExecutionStep> steps = ExecutionPlanAdapter.suiteSteps(json.readTree("""
                {"targetType":"TEST_SUITE","suiteSteps":[
                  {"memberId":"m-2","position":2,"targetType":"SCENARIO","targetId":"s-2","enabled":true,"plan":{"scenarioSteps":[]}},
                  {"memberId":"m-1","position":1,"targetType":"API_CASE","targetId":"c-1","enabled":false,"plan":{"planId":"p-1"}}
                ]}
                """));

        assertEquals(List.of("m-1", "m-2"), steps.stream().map(SuiteExecutionStep::memberId).toList());
        assertFalse(steps.get(0).enabled());
        assertEquals("API_CASE", steps.get(0).targetType());
    }

    @Test
    void prefixesNestedScenarioStepIdsWithSuiteMemberIdWithoutMutatingPlan() throws Exception {
        var input = json.readTree("""
                {"scenarioSteps":[
                  {"stepId":"root","kind":"WAIT","parentId":null,"plan":{}},
                  {"stepId":"child","kind":"WAIT","parentId":"root","plan":{}}
                ]}
                """);

        var prefixed = ExecutionPlanAdapter.prefixMemberStepIds(input, "member-1");

        assertEquals("member-1/root", prefixed.path("scenarioSteps").get(0).path("stepId").asText());
        assertEquals("member-1/child", prefixed.path("scenarioSteps").get(1).path("stepId").asText());
        assertEquals("member-1/root", prefixed.path("scenarioSteps").get(1).path("parentId").asText());
        assertEquals("root", input.path("scenarioSteps").get(0).path("stepId").asText());
    }

    @Test
    void rejectsUnknownSensitivePlanValuesBeforeJmeterExecution() throws Exception {
        var plan = json.readTree("""
                {"planId":"unsafe","baseUrl":"http://127.0.0.1","method":"GET",
                 "urlTemplate":"/","body":{"type":"NONE"},
                 "variables":{"token":"plain-text"}}
                """);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ExecutionPlanAdapter.fromJson(plan));
    }

    @Test
    void rejectsPlainSensitiveHeaderAndCookieValuesBeforeJmeterExecution() throws Exception {
        var plan = json.readTree("""
                {"planId":"unsafe-header","baseUrl":"https://example.test","method":"GET",
                 "urlTemplate":"/","headers":[{"name":"X-Api-Key","value":"plain-text"}],
                 "cookies":[{"name":"session","value":"plain-text"}],"body":{"type":"NONE"}}
                """);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ExecutionPlanAdapter.fromJson(plan));
    }

    @Test
    void rejectsHttpTargetOutsidePersistedProjectAllowlistBeforeJmeterExecution() throws Exception {
        var plan = json.readTree("""
                {"planId":"blocked-target","baseUrl":"http://127.0.0.1:18081","method":"GET",
                 "urlTemplate":"/health","targetAllowlist":["example.test"],"body":{"type":"NONE"}}
                """);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ExecutionPlanAdapter.fromJson(plan));
    }

    @Test
    void acceptsExplicitlyAllowlistedPrivateTargetBeforeJmeterExecution() throws Exception {
        var plan = json.readTree("""
                {"planId":"allowed-private","baseUrl":"http://127.0.0.1:18081","method":"GET",
                 "urlTemplate":"/health","targetAllowlist":["127.0.0.1:18081"],
                 "targetPolicyRequired":true,"body":{"type":"NONE"}}
                """);

        JmeterPlan resolved = ExecutionPlanAdapter.fromJson(plan);

        assertEquals("http://127.0.0.1:18081", resolved.baseUrl());
        assertEquals("/health", resolved.urlTemplate());
    }

    @Test
    void preservesRootTargetDnsRequirementAndCompleteAllowlist() throws Exception {
        List<String> allowlist = List.of("target:8080", "172.31.90.10:8080", "missing-target:8080");
        var input = json.readTree("""
                {"planId":"root-target-policy","baseUrl":"http://target:8080","method":"GET",
                 "urlTemplate":"/health","targetAllowlist":["target:8080","172.31.90.10:8080","missing-target:8080"],
                 "targetPolicyRequired":true,"targetDnsRequired":true,"body":{"type":"NONE"}}
                """);

        JmeterPlan resolved = ExecutionPlanAdapter.fromJson(input);

        assertTrue(resolved.targetPolicyRequired());
        assertTrue(resolved.targetDnsRequired());
        assertEquals(allowlist, resolved.targetAllowlist());
    }

    @Test
    void acceptsNormalizedTargetPolicySnapshotWhenLegacyFieldIsAbsent() throws Exception {
        JmeterPlan resolved = ExecutionPlanAdapter.fromJson(json.readTree("""
                {"planId":"snapshot-policy","baseUrl":"http://127.0.0.1:18081","method":"GET",
                 "urlTemplate":"/health","targetPolicySnapshot":{"rules":["127.0.0.1:18081"]},
                 "targetPolicyRequired":true,"body":{"type":"NONE"}}
                """));

        assertEquals(List.of("127.0.0.1:18081"), resolved.targetAllowlist());
    }

    @Test
    void rejectsMissingAllowlistWhenPlatformMarksPolicyRequired() throws Exception {
        var plan = json.readTree("""
                {"planId":"missing-policy","baseUrl":"https://example.test","method":"GET",
                 "urlTemplate":"/health","targetPolicyRequired":true,"body":{"type":"NONE"}}
                """);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ExecutionPlanAdapter.fromJson(plan));
    }

    @Test
    void carriesProjectAllowlistIntoNestedScenarioPlan() throws Exception {
        var root = json.readTree("""
                {"targetAllowlist":["example.test"],"scenarioSteps":[
                  {"stepId":"http-1","kind":"HTTP","plan":{"planId":"nested",
                   "baseUrl":"https://example.test","method":"GET","urlTemplate":"/health",
                   "body":{"type":"NONE"}}}]}
                """);

        var steps = ExecutionPlanAdapter.scenarioSteps(root);
        assertEquals("example.test", steps.get(0).plan().path("targetAllowlist").get(0).asText());
    }

    @Test
    void acceptsBearerAndBasicSecretTemplatesWithoutRegexFailure() throws Exception {
        var plan = json.readTree("""
                {"planId":"safe-template","baseUrl":"https://example.test","method":"GET",
                 "urlTemplate":"/","headers":[{"name":"Authorization","value":"Bearer ${secret:token}"}],
                 "body":{"type":"NONE"}}
                """);
        JmeterPlan resolved = ExecutionPlanAdapter.fromJson(plan);
        assertEquals("Bearer ${secret:token}", resolved.headers().get(0).value());
    }

    @Test
    void readsExecutableFieldsFromAssetContentEnvelope() throws Exception {
        JmeterPlan plan = ExecutionPlanAdapter.fromJson(json.readTree("""
                {"planId":"envelope","stepId":"step-1","jmeterVersion":"5.6.3",
                 "baseUrl":"http://127.0.0.1","method":"GET","urlTemplate":"/health",
                 "body":{"type":"NONE"},"assetContent":{"caseId":"case-1"}}
                """));

        assertEquals("envelope", plan.planId());
        assertEquals("/health", plan.urlTemplate());
    }

    @Test
    void preservesHttpBodyTypeAndCookieOptionsInExecutionPlan() throws Exception {
        JmeterPlan plan = ExecutionPlanAdapter.fromJson(json.readTree("""
                {
                  "planId":"http-options",
                  "baseUrl":"https://example.test",
                  "method":"POST",
                  "urlTemplate":"/upload",
                  "headers":[],
                  "cookies":[{"name":"session","value":"${secret:session}"}],
                  "followRedirects":false,
                  "connectTimeoutMillis":1500,
                  "responseTimeoutMillis":4500,
                  "proxy":{"scheme":"http","host":"proxy.example","port":8080,"username":"robot","password":"${secret:proxy-password}"},
                  "body":{"type":"URLENCODED","value":[{"name":"q","value":"a b","enabled":true}]},
                  "variables":{},
                  "assertions":[]
                }
                """));

        assertEquals("URLENCODED", plan.body().type());
        assertEquals(1, plan.cookies().size());
        assertEquals("session", plan.cookies().get(0).name());
        assertFalse(plan.followRedirects());
        assertEquals(1500, plan.connectTimeoutMillis());
        assertEquals(4500, plan.responseTimeoutMillis());
        assertEquals("proxy.example", plan.proxy().host());
        assertEquals(8080, plan.proxy().port());
    }

    @Test
    void resolvesPlanVariablesBeforeJmeterCompilation() throws Exception {
        JmeterPlan plan = ExecutionPlanAdapter.fromJson(json.readTree("""
                {
                  "planId":"resolved",
                  "baseUrl":"http://example.test",
                  "method":"POST",
                  "urlTemplate":"/orders/${orderId}",
                  "headers":[{"name":"X-Order","value":"${orderId}","enabled":true}],
                  "body":{"type":"JSON","value":{"id":"${orderId}","label":"order-${orderId}"}},
                  "variables":{"orderId":42},
                  "assertions":[]
                }
                """));

        assertEquals("/orders/42", plan.urlTemplate());
        assertEquals("42", plan.headers().get(0).value());
        assertEquals(42, plan.body().value().get("id").asInt());
        assertEquals("order-42", plan.body().value().get("label").asText());
    }

    @Test
    void resolvesDataRowScopeWithHigherPriorityWithoutMutatingInputPlan() throws Exception {
        var input = json.readTree("""
                {"planId":"row-plan","baseUrl":"http://example.test","method":"POST",
                 "urlTemplate":"/orders/${orderId}","headers":[],
                 "body":{"type":"JSON","value":{"id":"${orderId}","tenant":"${tenant}"}},
                 "variables":{"orderId":"case-1","tenant":"case-tenant"},
                 "variableScopes":{"caseVariables":{"orderId":"case-1","tenant":"case-tenant"}},
                 "assertions":[]}
                """);

        JmeterPlan plan = ExecutionPlanAdapter.fromJson(input, null, Map.of(
                "orderId", json.readTree("1001"), "tenant", json.readTree("\"row-tenant\"")));

        assertEquals("/orders/1001", plan.urlTemplate());
        assertEquals(1001, plan.body().value().get("id").asInt());
        assertEquals("row-tenant", plan.body().value().get("tenant").asText());
        assertEquals("/orders/${orderId}", input.path("urlTemplate").asText());
    }

    @Test
    void mapsJsonPathExtractorsWithDefaultValueAndVariableName() throws Exception {
        JmeterPlan plan = ExecutionPlanAdapter.fromJson(json.readTree("""
                {"planId":"extract","baseUrl":"http://example.test","method":"GET",
                 "urlTemplate":"/orders","body":{"type":"NONE"},
                 "extractors":[{"type":"JSON_PATH","expression":"$.token",
                    "variable":"accessToken","defaultValue":"missing"}]}
                """));

        assertEquals(1, plan.extractors().size());
        assertEquals("$.token", plan.extractors().get(0).expression());
        assertEquals("accessToken", plan.extractors().get(0).variable());
        assertEquals("missing", plan.extractors().get(0).defaultValue());
        assertTrue(plan.extractors().get(0).failIfMissing());
    }

    @Test
    void preservesAbsentNullAndEmptyExtractorDefaultsAsDifferentFacts() throws Exception {
        JmeterPlan plan = ExecutionPlanAdapter.fromJson(json.readTree("""
                {"planId":"defaults","baseUrl":"http://example.test","method":"GET",
                 "urlTemplate":"/orders","body":{"type":"NONE"},"extractors":[
                   {"type":"JSON_PATH","expression":"$.absent","variable":"absent"},
                   {"type":"JSON_PATH","expression":"$.nullValue","variable":"explicitNull","defaultValue":null},
                   {"type":"JSON_PATH","expression":"$.emptyValue","variable":"explicitEmpty","defaultValue":""}
                 ]}
                """));

        assertFalse(plan.extractors().get(0).defaultConfigured());
        assertTrue(plan.extractors().get(1).defaultConfigured());
        assertTrue(plan.extractors().get(2).defaultConfigured());
        assertNull(plan.extractors().get(0).defaultValue());
        assertNull(plan.extractors().get(1).defaultValue());
        assertEquals("", plan.extractors().get(2).defaultValue());
    }

    @Test
    void materializesPkcs12CertificateFromOpaqueSecretReferencesOnly() throws Exception {
        Path root = Files.createTempDirectory("f2-01-pkcs12-");
        String certificate = Base64.getEncoder().encodeToString("pkcs12-bytes".getBytes(StandardCharsets.UTF_8));
        var input = json.readTree("""
                {"projectId":"00000000-0000-0000-0000-000000000001","planId":"cert-plan",
                 "baseUrl":"https://example.test","method":"GET","urlTemplate":"/health",
                 "body":{"type":"NONE"},
                 "clientCertificate":{"type":"PKCS12","secretRef":"${secret:client-cert}",
                                      "passwordRef":"${secret:client-cert-password}"}}
                """);

        JmeterPlan plan;
        try (SecretFileMaterializer materializer = new SecretFileMaterializer(root,
                java.util.UUID.fromString(input.path("projectId").asText()),
                (project, name) -> name.equals("client-cert") ? certificate : "password-sentinel")) {
            plan = ExecutionPlanAdapter.fromJson(input, materializer);
            assertTrue(Files.isRegularFile(plan.clientCertificate().file()));
            assertEquals("password-sentinel", plan.clientCertificate().password());
            assertFalse(plan.clientCertificate().toString().contains("password-sentinel"));
        }
        assertFalse(Files.exists(root.resolve("secret-files")));
    }

    @Test
    void rejectsPkcs12CertificateWithoutRunnerSecretContext() throws Exception {
        var input = json.readTree("""
                {"planId":"cert-plan","baseUrl":"https://example.test","method":"GET","urlTemplate":"/",
                 "body":{"type":"NONE"},
                 "clientCertificate":{"type":"PKCS12","secretRef":"${secret:client-cert}",
                                      "passwordRef":"${secret:client-cert-password}"}}
                """);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ExecutionPlanAdapter.fromJson(input));
    }

    @Test
    void readsFirstSliceScenarioStepsAndWaitConfiguration() throws Exception {
        List<ScenarioExecutionStep> steps = ExecutionPlanAdapter.scenarioSteps(json.readTree("""
                {
                  "scenarioSteps":[
                    {"stepId":"login","kind":"API_CASE","enabled":true,
                     "failureStrategy":"STOP","plan":{"planId":"login-plan","stepId":"login",
                     "baseUrl":"http://example.test","method":"GET","urlTemplate":"/login",
                     "body":{"type":"NONE"}}},
                    {"stepId":"pause","kind":"WAIT","enabled":true,
                     "waitMillis":25,"failureStrategy":"CONTINUE"}
                  ]
                }
                """));

        assertEquals(2, steps.size());
        assertEquals("API_CASE", steps.get(0).kind());
        assertEquals("login", steps.get(0).stepId());
        assertEquals("/login", steps.get(0).plan().path("urlTemplate").asText());
        assertEquals("WAIT", steps.get(1).kind());
        assertEquals(25, steps.get(1).waitMillis());
    }
}
