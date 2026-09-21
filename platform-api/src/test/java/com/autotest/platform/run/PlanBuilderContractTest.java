package com.autotest.platform.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.autotest.platform.api.ApiCaseRecord;
import com.autotest.platform.api.ApiCaseRepository;
import com.autotest.platform.api.ApiDefinitionRecord;
import com.autotest.platform.api.ApiDefinitionRepository;
import com.autotest.platform.environment.EnvironmentRecord;
import com.autotest.platform.environment.EnvironmentRepository;
import com.autotest.platform.file.FileAssetRecord;
import com.autotest.platform.file.FileAssetRepository;
import com.autotest.platform.secret.SecretRepository;
import com.autotest.platform.security.ApiDomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanBuilderContractTest {
    @Test
    void clientExecutionPlanIsAlwaysRejected() throws Exception {
        var request = new ObjectMapper().readTree("{\"targetType\":\"API_CASE\",\"executionPlan\":{}}");

        var error = assertThrows(ApiDomainException.class,
                () -> PlanBuilder.rejectClientExecutionPlan(request));

        assertEquals("PLAN_TAMPERED", error.code());
    }

    @Test
    void savedDefinitionIncludesEnvironmentVariablesInVariableScopes() throws Exception {
        ObjectMapper json = new ObjectMapper();
        UUID projectId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        ApiDefinitionRepository definitions = mock(ApiDefinitionRepository.class);
        ApiCaseRepository cases = mock(ApiCaseRepository.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        FileAssetRepository assets = mock(FileAssetRepository.class);
        SecretRepository secrets = mock(SecretRepository.class);
        ApiDefinitionRecord definition = new ApiDefinitionRecord(definitionId, projectId, null, "tenant-definition",
                "GET", "/orders", json.readTree("""
                {"pathParams":[],"query":[{"name":"tenant","value":"${tenant}","enabled":true}],
                 "headers":[],"cookies":[],"body":{"type":"NONE"},"options":{}}
                """), 0, false, null, null);
        EnvironmentRecord environment = new EnvironmentRecord(environmentId, projectId, "local",
                "http://target:8080", json.createObjectNode().put("tenant", "demo"),
                json.createObjectNode(), 0, false, Instant.now(), Instant.now());
        when(definitions.findById(projectId, definitionId)).thenReturn(definition);
        when(environments.findById(projectId, environmentId)).thenReturn(environment);

        var plan = new PlanBuilder(definitions, cases, environments, json, assets, secrets)
                .buildSavedDefinition(projectId, definitionId, environmentId);

        assertEquals("demo", plan.path("variableScopes").path("environment").path("tenant").asText());
    }

    @Test
    void savedDefinitionMergesEnvironmentAndDefinitionHeaders() throws Exception {
        ObjectMapper json = new ObjectMapper();
        UUID projectId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        ApiDefinitionRepository definitions = mock(ApiDefinitionRepository.class);
        ApiCaseRepository cases = mock(ApiCaseRepository.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        ApiDefinitionRecord definition = new ApiDefinitionRecord(definitionId, projectId, null, "headers",
                "GET", "/orders", json.readTree("""
                {"pathParams":[],"query":[],"headers":[
                  {"name":"X-Override","value":"definition","enabled":true},
                  {"name":"X-Definition","value":"definition-only","enabled":true}],
                 "cookies":[],"body":{"type":"NONE"}}
                """), 0, false, null, null);
        var environmentOptions = json.createObjectNode();
        environmentOptions.set("defaultHeaders", json.readTree("""
                [{"name":"X-Env","value":"env","enabled":true},
                 {"name":"x-override","value":"environment","enabled":false}]
                """));
        EnvironmentRecord environment = new EnvironmentRecord(environmentId, projectId, "local",
                "http://target:8080", json.createObjectNode(), environmentOptions, 0,
                false, Instant.now(), Instant.now());
        when(definitions.findById(projectId, definitionId)).thenReturn(definition);
        when(environments.findById(projectId, environmentId)).thenReturn(environment);

        var headers = new PlanBuilder(definitions, cases, environments, json)
                .buildSavedDefinition(projectId, definitionId, environmentId).path("headers");

        assertEquals(3, headers.size());
        assertEquals("env", headers.get(0).path("value").asText());
        assertEquals("definition", headers.get(1).path("value").asText());
        assertTrue(headers.get(1).path("enabled").asBoolean());
        assertEquals("definition-only", headers.get(2).path("value").asText());
    }

    @Test
    void savedDefinitionResolvesPathAndKeepsEffectiveDefinitionPathParamValue() throws Exception {
        ObjectMapper json = new ObjectMapper();
        UUID projectId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        ApiDefinitionRepository definitions = mock(ApiDefinitionRepository.class);
        ApiCaseRepository cases = mock(ApiCaseRepository.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        ApiDefinitionRecord definition = new ApiDefinitionRecord(definitionId, projectId, null, "订单",
                "GET", "/orders/{orderId}", json.readTree("""
                {"pathParams":[{"name":"orderId","value":"1001","enabled":true}],"query":[],
                 "headers":[{"name":"x-shared","value":"definition","enabled":false},
                 {"name":"X-Definition-Only","value":"definition-only","enabled":true}],
                 "cookies":[],"body":{"type":"NONE"}}
                """), 0, false, null, null);
        EnvironmentRecord environment = new EnvironmentRecord(environmentId, projectId, "local",
                "http://target:8080", json.createObjectNode(), json.readTree("""
                {"defaultHeaders":[{"name":"X-Shared","value":"environment","enabled":true},
                 {"name":"X-Environment-Only","value":"environment-only","enabled":false}]}
                """), 0,
                false, Instant.now(), Instant.now());
        when(definitions.findById(projectId, definitionId)).thenReturn(definition);
        when(environments.findById(projectId, environmentId)).thenReturn(environment);

        var plan = new PlanBuilder(definitions, cases, environments, json)
                .buildSavedDefinition(projectId, definitionId, environmentId);

        assertEquals("/orders/1001", plan.path("urlTemplate").asText());
        assertEquals("1001", plan.path("pathParams").get(0).path("value").asText());
        assertEquals(json.readTree("""
                [{"name":"x-shared","value":"definition","enabled":false},
                 {"name":"X-Environment-Only","value":"environment-only","enabled":false},
                 {"name":"X-Definition-Only","value":"definition-only","enabled":true}]
                """), plan.path("headers"));
    }

    @Test
    void savedDefinitionResolvesPathParametersInOnePass() throws Exception {
        ObjectMapper json = new ObjectMapper();
        UUID projectId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        ApiDefinitionRepository definitions = mock(ApiDefinitionRepository.class);
        ApiCaseRepository cases = mock(ApiCaseRepository.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        ApiDefinitionRecord definition = new ApiDefinitionRecord(definitionId, projectId, null, "多路径",
                "GET", "/orders/{tenant}/{orderId}", json.readTree("""
                {"pathParams":[{"name":"tenant","value":"${orderId}","enabled":true},
                 {"name":"orderId","value":"2002","enabled":true}],"query":[],
                 "headers":[],"cookies":[],"body":{"type":"NONE"}}
                """), 0, false, null, null);
        EnvironmentRecord environment = new EnvironmentRecord(environmentId, projectId, "local",
                "http://target:8080", json.createObjectNode(), json.createObjectNode(), 0,
                false, Instant.now(), Instant.now());
        when(definitions.findById(projectId, definitionId)).thenReturn(definition);
        when(environments.findById(projectId, environmentId)).thenReturn(environment);

        var plan = new PlanBuilder(definitions, cases, environments, json)
                .buildSavedDefinition(projectId, definitionId, environmentId);

        assertEquals("/orders/${orderId}/2002", plan.path("urlTemplate").asText());
    }

    @Test
    void savedCaseOverrideResolvesPathAndUpdatesEffectivePathParamValue() throws Exception {
        ObjectMapper json = new ObjectMapper();
        UUID projectId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        ApiDefinitionRepository definitions = mock(ApiDefinitionRepository.class);
        ApiCaseRepository cases = mock(ApiCaseRepository.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        ApiDefinitionRecord definition = new ApiDefinitionRecord(definitionId, projectId, null, "订单",
                "GET", "/orders/{orderId}", json.readTree("""
                {"pathParams":[{"name":"orderId","value":"${orderId}","enabled":true}],"query":[],
                 "headers":[],"cookies":[],"body":{"type":"NONE"}}
                """), 0, false, null, null);
        ApiCaseRecord apiCase = new ApiCaseRecord(caseId, projectId, definitionId, "订单 2002", json.readTree("""
                {"pathParams":{"orderId":"2002"},"query":{},"headers":{},"cookies":{},
                 "body":{"type":"NONE"},"extractors":[]}
                """), json.createObjectNode(), json.createArrayNode(), 0, false, null, null);
        EnvironmentRecord environment = new EnvironmentRecord(environmentId, projectId, "local",
                "http://target:8080", json.createObjectNode(), json.createObjectNode(), 0,
                false, Instant.now(), Instant.now());
        when(cases.findActiveByProjectId(projectId, caseId)).thenReturn(apiCase);
        when(definitions.findById(projectId, definitionId)).thenReturn(definition);
        when(environments.findById(projectId, environmentId)).thenReturn(environment);

        var plan = new PlanBuilder(definitions, cases, environments, json)
                .buildSavedCase(projectId, caseId, environmentId);

        assertEquals("/orders/2002", plan.path("urlTemplate").asText());
        assertEquals("2002", plan.path("pathParams").get(0).path("value").asText());
    }

    @Test
    void savedCaseWithoutPathOverrideKeepsDefinitionPathParamValue() throws Exception {
        ObjectMapper json = new ObjectMapper();
        UUID projectId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        ApiDefinitionRepository definitions = mock(ApiDefinitionRepository.class);
        ApiCaseRepository cases = mock(ApiCaseRepository.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        ApiDefinitionRecord definition = new ApiDefinitionRecord(definitionId, projectId, null, "订单",
                "GET", "/orders/{orderId}", json.readTree("""
                {"pathParams":[{"name":"orderId","value":"${orderId}","enabled":true}],"query":[],
                 "headers":[],"cookies":[],"body":{"type":"NONE"}}
                """), 0, false, null, null);
        ApiCaseRecord apiCase = new ApiCaseRecord(caseId, projectId, definitionId, "订单默认", json.readTree("""
                {"pathParams":{},"query":{},"headers":{},"cookies":{},"body":{"type":"NONE"},"extractors":[]}
                """), json.createObjectNode(), json.createArrayNode(), 0, false, null, null);
        EnvironmentRecord environment = new EnvironmentRecord(environmentId, projectId, "local",
                "http://target:8080", json.createObjectNode(), json.createObjectNode(), 0,
                false, Instant.now(), Instant.now());
        when(cases.findActiveByProjectId(projectId, caseId)).thenReturn(apiCase);
        when(definitions.findById(projectId, definitionId)).thenReturn(definition);
        when(environments.findById(projectId, environmentId)).thenReturn(environment);

        var plan = new PlanBuilder(definitions, cases, environments, json)
                .buildSavedCase(projectId, caseId, environmentId);

        assertEquals("/orders/${orderId}", plan.path("urlTemplate").asText());
        assertEquals("${orderId}", plan.path("pathParams").get(0).path("value").asText());
    }

    @Test
    void multipartTextAndFileEntriesBuildSnapshotsWithoutTreatingTextAsFile() throws Exception {
        ObjectMapper json = new ObjectMapper();
        UUID projectId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        ApiDefinitionRepository definitions = mock(ApiDefinitionRepository.class);
        ApiCaseRepository cases = mock(ApiCaseRepository.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        FileAssetRepository assets = mock(FileAssetRepository.class);
        SecretRepository secrets = mock(SecretRepository.class);
        ApiDefinitionRecord definition = new ApiDefinitionRecord(definitionId, projectId, null, "multipart",
                "POST", "/echo", json.readTree("""
                {"pathParams":[],"query":[],"headers":[],"cookies":[],
                 "body":{"type":"MULTIPART","value":[
                   {"name":"note","kind":"TEXT","value":"text","enabled":true},
                   {"name":"upload","kind":"FILE","fileId":"%s","contentType":"text/plain","enabled":true}]},
                 "options":{}}
                """.formatted(fileId)), 0, false, null, null);
        EnvironmentRecord environment = new EnvironmentRecord(environmentId, projectId, "local",
                "http://echo-target:8080", json.createObjectNode(), json.createObjectNode(), 0,
                false, Instant.now(), Instant.now());
        when(definitions.findById(projectId, definitionId)).thenReturn(definition);
        when(environments.findById(projectId, environmentId)).thenReturn(environment);
        when(assets.find(projectId, fileId)).thenReturn(new FileAssetRecord(fileId, projectId, "REQUEST_FILE",
                "payload.txt", "text/plain", 4, "sha", "ACTIVE", "private/key", 0,
                Instant.now(), Instant.now()));
        PlanBuilder builder = new PlanBuilder(definitions, cases, environments, json, assets, secrets);

        var plan = assertDoesNotThrow(() -> builder.buildSavedDefinition(projectId, definitionId, environmentId));

        assertEquals("text", plan.path("body").path("value").get(0).path("value").asText());
        assertEquals("payload.txt", plan.path("body").path("value").get(1).path("originalName").asText());
    }

    @Test
    void clientCertificateSnapshotIsCopiedToRuntimeOptionsAndTopLevelPlan() throws Exception {
        ObjectMapper json = new ObjectMapper();
        UUID projectId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        ApiDefinitionRepository definitions = mock(ApiDefinitionRepository.class);
        ApiCaseRepository cases = mock(ApiCaseRepository.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        FileAssetRepository assets = mock(FileAssetRepository.class);
        SecretRepository secrets = mock(SecretRepository.class);
        ApiDefinitionRecord definition = new ApiDefinitionRecord(definitionId, projectId, null, "mtls",
                "GET", "/health", json.readTree("""
                {"pathParams":[],"query":[],"headers":[],"cookies":[],"body":{"type":"NONE"},
                 "options":{"clientCertificate":{"type":"PKCS12","fileId":"%s",
                 "passwordSecretRef":"${secret:p12-pass}"}}}
                """.formatted(fileId)), 0, false, null, null);
        EnvironmentRecord environment = new EnvironmentRecord(environmentId, projectId, "local",
                "https://mtls-target:8443", json.createObjectNode(), json.createObjectNode(), 0,
                false, Instant.now(), Instant.now());
        when(definitions.findById(projectId, definitionId)).thenReturn(definition);
        when(environments.findById(projectId, environmentId)).thenReturn(environment);
        when(assets.find(projectId, fileId)).thenReturn(new FileAssetRecord(fileId, projectId, "PKCS12",
                "client.p12", "application/x-pkcs12", 6, "a1b2c3", "ACTIVE", "private/client-p12", 0,
                Instant.now(), Instant.now()));

        var plan = new PlanBuilder(definitions, cases, environments, json, assets, secrets)
                .buildSavedDefinition(projectId, definitionId, environmentId);

        assertEquals(fileId.toString(), plan.path("options").path("clientCertificate")
                .path("fileSnapshot").path("fileId").asText());
        assertEquals(fileId.toString(), plan.path("clientCertificate")
                .path("fileSnapshot").path("fileId").asText());
    }
}
