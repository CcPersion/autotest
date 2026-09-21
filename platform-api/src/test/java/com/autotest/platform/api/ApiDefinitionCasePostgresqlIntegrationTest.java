package com.autotest.platform.api;

import com.autotest.platform.PlatformApiApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class ApiDefinitionCasePostgresqlIntegrationTest {

    private static final String ADMIN_PASSWORD = "F1-05-password-123!";
    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(
            "F104-test-master-key-32-bytes!!!".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("autotest")
            .withUsername("autotest")
            .withPassword("test-only-password");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void previewsCurlAndOpenApiWithoutWritesThenConfirmsExplicitly() throws Exception {
        try (ConfigurableApplicationContext context = startApplication()) {
            String baseUrl = baseUrl(context);
            Response login = request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\"owner@example.com\",\"password\":\"" + ADMIN_PASSWORD + "\"}", null);
            assertEquals(200, login.status());
            Session session = new Session(cookie(login, "JSESSIONID"), cookie(login, "XSRF-TOKEN"));
            String projectId = field(request(baseUrl, "POST", "/api/v1/projects",
                    "{\"name\":\"F3-02 import " + System.nanoTime() + "\"}", session), "id");
            String definitionsPath = "/api/v1/projects/" + projectId + "/api-definitions";
            assertEquals(200, request(baseUrl, "GET", definitionsPath, null, session).status());
            assertEquals(0, json.readTree(request(baseUrl, "GET", definitionsPath, null, session).body()).size());

            Response curlPreview = request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/imports/curl/preview",
                    "{\"source\":\"curl -X POST https://example.test/orders -H 'Content-Type: application/json' -d '{\\\"id\\\":1}'\"}", session);
            assertEquals(200, curlPreview.status(), curlPreview.body());
            String previewId = field(curlPreview, "previewId");
            assertEquals(0, json.readTree(request(baseUrl, "GET", definitionsPath, null, session).body()).size());

            Response confirmed = request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/imports/confirm",
                    "{\"previewId\":\"" + previewId + "\",\"choices\":[{\"index\":0,\"action\":\"CREATE\"}]}", session);
            assertEquals(201, confirmed.status(), confirmed.body());
            assertEquals(1, json.readTree(request(baseUrl, "GET", definitionsPath, null, session).body()).size());

            String yaml = "openapi: 3.0.3\ninfo:\n  title: Import\n  version: '1'\npaths:\n  /health:\n    get:\n      responses:\n        '200':\n          description: ok\n";
            Response openApiPreview = request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/imports/openapi/preview",
                    "{\"source\":\"" + yaml.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}", session);
            assertEquals(200, openApiPreview.status(), openApiPreview.body());
            assertEquals(1, json.readTree(request(baseUrl, "GET", definitionsPath, null, session).body()).size());

            Response rejected = request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/imports/curl/preview",
                    "{\"source\":\"curl https://example.test/x?x=$(cat /tmp/key)\"}", session);
            assertEquals(400, rejected.status());
            assertTrue(rejected.body().contains("curl"));
        }
    }

    @Test
    void previewsAiPatchWithoutWriteAndConfirmsWithRevisionCas() throws Exception {
        try (ConfigurableApplicationContext context = startApplication()) {
            String baseUrl = baseUrl(context);
            Response login = request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\"owner@example.com\",\"password\":\"" + ADMIN_PASSWORD + "\"}", null);
            assertEquals(200, login.status());
            Session session = new Session(cookie(login, "JSESSIONID"), cookie(login, "XSRF-TOKEN"));
            String projectId = field(request(baseUrl, "POST", "/api/v1/projects",
                    "{\"name\":\"F3-03 patch " + System.nanoTime() + "\"}", session), "id");
            String definitionsPath = "/api/v1/projects/" + projectId + "/api-definitions";
            Response created = request(baseUrl, "POST", definitionsPath,
                    "{\"name\":\"原始接口\",\"method\":\"GET\",\"urlTemplate\":\"/health\",\"requestSpec\":"
                            + noneRequestSpec() + "}", session);
            assertEquals(201, created.status(), created.body());
            String definitionId = field(created, "id");

            Response preview = request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/ai/patches/preview",
                    "{\"title\":\"调整接口名称\",\"targetType\":\"API_DEFINITION\",\"targetId\":\""
                            + definitionId + "\",\"baseRevision\":0,\"operations\":[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"AI 接口\"}]}", session);
            assertEquals(200, preview.status(), preview.body());
            String previewId = field(preview, "previewId");
            assertEquals("MODIFIED", json.readTree(preview.body()).path("changes").get(0).path("changeType").asText());
            Response beforeConfirm = request(baseUrl, "GET", definitionsPath + "/" + definitionId, null, session);
            assertEquals(200, beforeConfirm.status());
            assertEquals("原始接口", field(beforeConfirm, "name"));
            assertEquals(0, intField(beforeConfirm, "revision"));

            Response confirmed = request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/ai/patches/confirm",
                    "{\"previewId\":\"" + previewId + "\"}", session);
            assertEquals(200, confirmed.status(), confirmed.body());
            assertEquals("AI 接口", json.readTree(confirmed.body()).path("asset").path("name").asText());
            assertEquals(1, intField(confirmed, "revision"));
            Response afterConfirm = request(baseUrl, "GET", definitionsPath + "/" + definitionId, null, session);
            assertEquals("AI 接口", field(afterConfirm, "name"));
            assertEquals(1, intField(afterConfirm, "revision"));

            Response replay = request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/ai/patches/confirm",
                    "{\"previewId\":\"" + previewId + "\"}", session);
            assertEquals(404, replay.status());
            Response stale = request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/ai/patches/preview",
                    "{\"title\":\"过期草稿\",\"targetType\":\"API_DEFINITION\",\"targetId\":\""
                            + definitionId + "\",\"baseRevision\":0,\"operations\":[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"旧版本\"}]}", session);
            assertEquals(409, stale.status());
            Response script = request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/ai/patches/preview",
                    "{\"title\":\"恶意\",\"targetType\":\"API_DEFINITION\",\"targetId\":\""
                            + definitionId + "\",\"baseRevision\":1,\"operations\":[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"$(whoami)\"}]}", session);
            assertEquals(400, script.status());
            assertFalse(script.body().contains("whoami"));
        }
    }

    @Test
    void managesJsonDefinitionsAndCasesWithProjectIsolationAndCas() throws Exception {
        try (ConfigurableApplicationContext context = startApplication()) {
            String baseUrl = baseUrl(context);
            Response login = request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\"owner@example.com\",\"password\":\"" + ADMIN_PASSWORD + "\"}", null);
            assertEquals(200, login.status());
            Session session = new Session(cookie(login, "JSESSIONID"), cookie(login, "XSRF-TOKEN"));

            Response malformedJson = request(baseUrl, "POST", "/api/v1/projects", "{", session);
            assertEquals(400, malformedJson.status());
            assertTrue(malformedJson.body().contains("INVALID_REQUEST"));
            assertTrue(malformedJson.body().contains("\"path\":\"$\""));

            String projectId = field(request(baseUrl, "POST", "/api/v1/projects",
                    "{\"name\":\"F1-05 project\"}", session), "id");
            String otherProjectId = field(request(baseUrl, "POST", "/api/v1/projects",
                    "{\"name\":\"F1-05 other\"}", session), "id");
            String moduleId = field(request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/modules",
                    "{\"name\":\"Orders\"}", session), "id");
            String foreignModuleId = field(request(baseUrl, "POST", "/api/v1/projects/" + otherProjectId + "/modules",
                    "{\"name\":\"Foreign\"}", session), "id");
            assertEquals(201, request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/secrets",
                    "{\"name\":\"client-cert\",\"value\":\"ZmFrZS1wYXlsb2Fk\"}", session).status());
            assertEquals(201, request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/secrets",
                    "{\"name\":\"client-cert-password\",\"value\":\"test-password\"}", session).status());

            for (InvalidDefinition invalid : invalidDefinitions()) {
                Response response = request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/api-definitions",
                        "{\"name\":\"" + invalid.name() + "\",\"method\":\"" + invalid.method()
                                + "\",\"urlTemplate\":\"" + invalid.urlTemplate() + "\",\"requestSpec\":"
                                + invalid.requestSpec() + "}", session);
                assertValidationError(response, invalid.fieldPath(), invalid.sentinel());
            }

            Response foreignModuleDefinition = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/api-definitions",
                    "{\"moduleId\":\"" + foreignModuleId + "\",\"name\":\"foreign\","
                            + "\"method\":\"GET\",\"urlTemplate\":\"/orders\","
                            + "\"requestSpec\":{\"body\":{\"type\":\"NONE\"}}}", session);
            assertEquals(404, foreignModuleDefinition.status());

            Response unknownDefinitionField = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/api-definitions",
                    "{\"name\":\"unknown-definition\",\"method\":\"GET\","
                            + "\"urlTemplate\":\"/unknown\",\"requestSpec\":" + noneRequestSpec()
                            + ",\"F105_UNKNOWN_DEFINITION_SENTINEL\":\"value\"}", session);
            assertInvalidRequestNoEcho(unknownDefinitionField, "F105_UNKNOWN_DEFINITION_SENTINEL");

            String requestSpec = "{\"pathParams\":[{\"name\":\"orderId\",\"value\":\"${orderId}\"}],"
                    + "\"query\":[{\"name\":\"verbose\",\"value\":\"true\",\"enabled\":true}],"
                    + "\"headers\":[{\"name\":\"X-Tenant\",\"value\":\"${tenant}\",\"enabled\":true}],"
                    + "\"body\":{\"type\":\"JSON\",\"value\":{\"orderId\":\"${orderId}\","
                    + "\"items\":[1,true,null]}}}";
            Response definition = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/api-definitions",
                    "{\"moduleId\":\"" + moduleId + "\",\"name\":\"Create Order\","
                            + "\"method\":\"POST\",\"urlTemplate\":\"/orders/{orderId}\","
                            + "\"requestSpec\":" + requestSpec + "}", session);
            assertEquals(201, definition.status());
            String definitionId = field(definition, "id");
            assertEquals(0, intField(definition, "revision"));
            JsonNode storedRequest = json.readTree(definition.body()).get("requestSpec");
            assertEquals("orderId", storedRequest.get("pathParams").get(0).get("name").asText());
            assertTrue(storedRequest.get("body").get("value").get("items").get(1).isBoolean());
            assertTrue(storedRequest.get("body").get("value").get("items").get(2).isNull());

            Response certificateDefinition = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/api-definitions",
                    "{\"moduleId\":\"" + moduleId + "\",\"name\":\"mTLS health\","
                            + "\"method\":\"GET\",\"urlTemplate\":\"/mtls\",\"requestSpec\":{"
                            + "\"pathParams\":[],\"query\":[],\"headers\":[],\"cookies\":[],"
                            + "\"body\":{\"type\":\"NONE\"},\"options\":{"
                            + "\"clientCertificate\":{\"type\":\"PKCS12\","
                            + "\"secretRef\":\"${secret:client-cert}\","
                            + "\"passwordRef\":\"${secret:client-cert-password}\"}}}}", session);
            assertEquals(201, certificateDefinition.status());
            assertTrue(certificateDefinition.body().contains("clientCertificate"));

            Response duplicateDefinition = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/api-definitions",
                    "{\"moduleId\":\"" + moduleId + "\",\"name\":\"create order\","
                            + "\"method\":\"GET\",\"urlTemplate\":\"/orders\","
                            + "\"requestSpec\":{\"pathParams\":[],\"query\":[],\"headers\":[],"
                            + "\"body\":{\"type\":\"NONE\"}}}", session);
            assertEquals(409, duplicateDefinition.status());
            assertTrue(duplicateDefinition.body().contains("NAME_CONFLICT"));

            Response definitionRead = request(baseUrl, "GET",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId, null, session);
            assertEquals(200, definitionRead.status());
            Response foreignDefinitionRead = request(baseUrl, "GET",
                    "/api/v1/projects/" + otherProjectId + "/api-definitions/" + definitionId, null, session);
            assertEquals(404, foreignDefinitionRead.status());

            Response foreignDefinition = request(baseUrl, "POST",
                    "/api/v1/projects/" + otherProjectId + "/api-definitions",
                    "{\"name\":\"Foreign Definition\",\"method\":\"GET\","
                            + "\"urlTemplate\":\"/foreign\",\"requestSpec\":" + noneRequestSpec() + "}", session);
            assertEquals(201, foreignDefinition.status());
            String foreignDefinitionId = field(foreignDefinition, "id");
            Response foreignCase = request(baseUrl, "POST",
                    "/api/v1/projects/" + otherProjectId + "/api-definitions/" + foreignDefinitionId + "/cases",
                    "{\"name\":\"Foreign Case\",\"caseSpec\":" + emptyCaseSpec()
                            + ",\"variables\":{},\"assertions\":[]}", session);
            assertEquals(201, foreignCase.status());
            String foreignCaseId = field(foreignCase, "id");
            assertEquals(404, request(baseUrl, "GET",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + foreignDefinitionId,
                    null, session).status());
            assertEquals(404, request(baseUrl, "GET",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId
                            + "/cases/" + foreignCaseId, null, session).status());
            assertEquals(404, request(baseUrl, "GET",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + foreignDefinitionId
                            + "/cases/" + foreignCaseId, null, session).status());

            Response definitionUpdate = request(baseUrl, "PUT",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId,
                    "{\"moduleId\":\"" + moduleId + "\",\"name\":\"Create Order v2\","
                            + "\"method\":\"POST\",\"urlTemplate\":\"/orders/{orderId}\","
                            + "\"requestSpec\":" + requestSpec + ",\"revision\":0}", session);
            assertEquals(200, definitionUpdate.status());
            assertEquals(1, intField(definitionUpdate, "revision"));
            JsonNode stableDefinition = json.readTree(definitionUpdate.body());
            Response staleDefinitionUpdate = request(baseUrl, "PUT",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId,
                    "{\"name\":\"stale\",\"method\":\"GET\",\"urlTemplate\":\"/stale\","
                            + "\"requestSpec\":{\"body\":{\"type\":\"NONE\"}},\"revision\":0}", session);
            assertEquals(409, staleDefinitionUpdate.status());
            assertTrue(staleDefinitionUpdate.body().contains("REVISION_CONFLICT"));

            String caseSpec = "{\"pathParams\":{\"orderId\":\"1001\"},"
                    + "\"query\":{\"verbose\":\"false\"},"
                    + "\"headers\":{\"X-Tenant\":\"qa\"},"
                    + "\"body\":{\"type\":\"JSON\",\"value\":{\"orderId\":1001}},"
                    + "\"extractors\":[{\"type\":\"JSON_PATH\",\"expression\":\"$.data.token\","
                    + "\"variable\":\"accessToken\",\"defaultValue\":\"missing\",\"failIfMissing\":true}]}";
            String assertions = "[{\"type\":\"STATUS\",\"operator\":\"EQUALS\",\"expected\":200},"
                    + "{\"type\":\"JSON_PATH\",\"expression\":\"$.data.id\",\"operator\":\"EXISTS\"},"
                    + "{\"type\":\"JSON_PATH\",\"expression\":\"$.data.state\","
                    + "\"operator\":\"EQUALS\",\"expected\":\"PAID\"},"
                    + "{\"type\":\"JMES_PATH\",\"expression\":\"data.id\",\"operator\":\"NOT_EXISTS\"},"
                    + "{\"type\":\"XPATH\",\"expression\":\"//data/id\",\"operator\":\"EXISTS\"},"
                    + "{\"type\":\"BODY\",\"operator\":\"CONTAINS\",\"expected\":\"PAID\"},"
                    + "{\"type\":\"HEADER\",\"expression\":\"Content-Type\",\"operator\":\"CONTAINS\",\"expected\":\"application/json\"},"
                    + "{\"type\":\"COOKIE\",\"expression\":\"sid\",\"operator\":\"NOT_CONTAINS\",\"expected\":\"expired\"},"
                    + "{\"type\":\"SCHEMA\",\"operator\":\"VALIDATE\",\"expected\":{"
                    + "\"type\":\"object\",\"required\":[\"data\"]}},"
                    + "{\"type\":\"RESPONSE_TIME\",\"operator\":\"LESS_THAN\",\"expected\":1000}]";
            for (InvalidCase invalid : invalidCases(caseSpec)) {
                Response response = request(baseUrl, "POST",
                        "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId + "/cases",
                        "{\"name\":\"" + invalid.name() + "\",\"caseSpec\":" + invalid.caseSpec()
                                + ",\"variables\":" + invalid.variables() + ",\"assertions\":"
                                + invalid.assertions() + "}", session);
                assertValidationError(response, invalid.fieldPath(), invalid.sentinel());
            }
            Response apiCase = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId + "/cases",
                    "{\"name\":\"happy path\",\"caseSpec\":" + caseSpec
                            + ",\"variables\":{\"orderId\":1001,\"enabled\":true,\"nothing\":null},"
                            + "\"assertions\":" + assertions + "}", session);
            assertEquals(201, apiCase.status());
            String caseId = field(apiCase, "id");
            JsonNode createdCase = json.readTree(apiCase.body());
            assertEquals(projectId, createdCase.path("projectId").asText());
            assertEquals(definitionId, field(apiCase, "apiDefinitionId"));
            assertEquals(0, intField(apiCase, "revision"));
            assertTrue(createdCase.get("variables").get("enabled").isBoolean());
            assertEquals("EXISTS", createdCase.get("assertions").get(1).get("operator").asText());
            assertEquals("accessToken", createdCase.get("caseSpec").get("extractors")
                    .get(0).get("variable").asText());

            String casesPath = "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId + "/cases";
            Response caseRead = request(baseUrl, "GET", casesPath + "/" + caseId, null, session);
            assertEquals(200, caseRead.status(), caseRead.body());
            JsonNode readCase = json.readTree(caseRead.body());
            assertEquals(projectId, readCase.path("projectId").asText());
            assertEquals(definitionId, readCase.path("apiDefinitionId").asText());
            assertEquals(createdCase.path("caseSpec"), readCase.path("caseSpec"));
            assertEquals(createdCase.path("variables"), readCase.path("variables"));
            assertEquals(createdCase.path("assertions"), readCase.path("assertions"));
            assertEquals(0, readCase.path("revision").asInt());

            Response activeCases = request(baseUrl, "GET", casesPath, null, session);
            assertEquals(200, activeCases.status(), activeCases.body());
            JsonNode activeCaseList = json.readTree(activeCases.body());
            assertTrue(activeCaseList.isArray());
            assertEquals(1, activeCaseList.size());
            assertEquals(caseId, activeCaseList.get(0).path("id").asText());

            Response unknownCaseField = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId + "/cases",
                    "{\"name\":\"unknown-case\",\"caseSpec\":" + caseSpec
                            + ",\"variables\":{},\"assertions\":" + assertions
                            + ",\"F105_UNKNOWN_CASE_SENTINEL\":\"value\"}", session);
            assertInvalidRequestNoEcho(unknownCaseField, "F105_UNKNOWN_CASE_SENTINEL");

            Response removeDeclaredQuery = request(baseUrl, "PUT",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId,
                    "{\"moduleId\":\"" + moduleId + "\",\"name\":\"F105_CHILD_REMOVE_QUERY_SENTINEL\","
                            + "\"method\":\"POST\",\"urlTemplate\":\"/orders/{orderId}\","
                            + "\"requestSpec\":" + requestSpecWithoutQuery() + ",\"revision\":1}", session);
            assertChildIncompatible(removeDeclaredQuery, "F105_CHILD_REMOVE_QUERY_SENTINEL");
            Response afterQueryRejection = request(baseUrl, "GET",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId, null, session);
            assertEquals(200, afterQueryRejection.status());
            assertEquals(1, intField(afterQueryRejection, "revision"));
            assertEquals("Create Order v2", field(afterQueryRejection, "name"));
            JsonNode afterQueryDefinition = json.readTree(afterQueryRejection.body());
            assertEquals(stableDefinition.path("requestSpec"), afterQueryDefinition.path("requestSpec"));
            assertEquals(stableDefinition.path("method"), afterQueryDefinition.path("method"));
            assertEquals(stableDefinition.path("urlTemplate"), afterQueryDefinition.path("urlTemplate"));

            Response postToGetNone = request(baseUrl, "PUT",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId,
                    "{\"moduleId\":\"" + moduleId + "\",\"name\":\"F105_CHILD_GET_NONE_SENTINEL\","
                            + "\"method\":\"GET\",\"urlTemplate\":\"/orders/{orderId}\","
                            + "\"requestSpec\":" + getNoneRequestSpec() + ",\"revision\":1}", session);
            assertChildIncompatible(postToGetNone, "F105_CHILD_GET_NONE_SENTINEL");
            Response afterBodyRejection = request(baseUrl, "GET",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId, null, session);
            assertEquals(200, afterBodyRejection.status());
            assertEquals(1, intField(afterBodyRejection, "revision"));
            JsonNode afterBodyDefinition = json.readTree(afterBodyRejection.body());
            assertEquals(stableDefinition.path("requestSpec"), afterBodyDefinition.path("requestSpec"));
            assertEquals(stableDefinition.path("method"), afterBodyDefinition.path("method"));
            assertEquals(stableDefinition.path("urlTemplate"), afterBodyDefinition.path("urlTemplate"));

            Response compatibleRename = request(baseUrl, "PUT",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId,
                    "{\"moduleId\":\"" + moduleId + "\",\"name\":\"Create Order v3\","
                            + "\"method\":\"POST\",\"urlTemplate\":\"/orders/{orderId}\","
                            + "\"requestSpec\":" + requestSpec + ",\"revision\":1}", session);
            assertEquals(200, compatibleRename.status());
            assertEquals(2, intField(compatibleRename, "revision"));

            Response duplicateCase = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId + "/cases",
                    "{\"name\":\"HAPPY PATH\",\"caseSpec\":" + caseSpec
                            + ",\"variables\":{},\"assertions\":" + assertions + "}", session);
            assertEquals(409, duplicateCase.status());
            assertTrue(duplicateCase.body().contains("NAME_CONFLICT"));

            Response invalidOverride = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId + "/cases",
                    "{\"name\":\"invalid override\",\"caseSpec\":{\"query\":{\"missing\":\"x\"}},"
                            + "\"variables\":{},\"assertions\":[]}", session);
            assertEquals(400, invalidOverride.status());
            assertTrue(invalidOverride.body().contains("VALIDATION_FAILED"));

            Response caseUpdate = request(baseUrl, "PUT",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId + "/cases/" + caseId,
                    "{\"name\":\"happy path updated\",\"caseSpec\":" + caseSpec
                            + ",\"variables\":{\"orderId\":1002},\"assertions\":" + assertions
                            + ",\"revision\":0}", session);
            assertEquals(200, caseUpdate.status());
            assertEquals(1, intField(caseUpdate, "revision"));
            Response staleCaseUpdate = request(baseUrl, "PUT",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId + "/cases/" + caseId,
                    "{\"name\":\"stale\",\"caseSpec\":{},\"variables\":{},\"assertions\":[],\"revision\":0}", session);
            assertEquals(409, staleCaseUpdate.status());
            assertTrue(staleCaseUpdate.body().contains("REVISION_CONFLICT"));

            Response caseArchive = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId + "/cases/" + caseId + "/archive",
                    "{\"revision\":1,\"F105_UNKNOWN_CASE_REVISION_SENTINEL\":\"value\"}", session);
            assertInvalidRequestNoEcho(caseArchive, "F105_UNKNOWN_CASE_REVISION_SENTINEL");
            caseArchive = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId + "/cases/" + caseId + "/archive",
                    "{\"revision\":1}", session);
            assertEquals(200, caseArchive.status());
            assertTrue(boolField(caseArchive, "archived"));
            Response activeCasesAfterArchive = request(baseUrl, "GET", casesPath, null, session);
            assertEquals(200, activeCasesAfterArchive.status(), activeCasesAfterArchive.body());
            assertEquals(0, json.readTree(activeCasesAfterArchive.body()).size());
            Response archivedCases = request(baseUrl, "GET", casesPath + "?includeArchived=true", null, session);
            assertEquals(200, archivedCases.status(), archivedCases.body());
            JsonNode archivedCaseList = json.readTree(archivedCases.body());
            assertEquals(1, archivedCaseList.size());
            assertEquals(caseId, archivedCaseList.get(0).path("id").asText());
            assertTrue(archivedCaseList.get(0).path("archived").asBoolean());

            Response definitionArchive = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId + "/archive",
                    "{\"revision\":2,\"F105_UNKNOWN_DEFINITION_REVISION_SENTINEL\":\"value\"}", session);
            assertInvalidRequestNoEcho(definitionArchive, "F105_UNKNOWN_DEFINITION_REVISION_SENTINEL");
            definitionArchive = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId + "/archive",
                    "{\"revision\":2}", session);
            assertEquals(200, definitionArchive.status());
            assertTrue(boolField(definitionArchive, "archived"));
            Response caseUnderArchivedDefinition = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/api-definitions/" + definitionId + "/cases",
                    "{\"name\":\"blocked\",\"caseSpec\":{},\"variables\":{},\"assertions\":[]}", session);
            assertEquals(409, caseUnderArchivedDefinition.status());
            assertTrue(caseUnderArchivedDefinition.body().contains("PARENT_ARCHIVED"));
            Response activeDefinitions = request(baseUrl, "GET",
                    "/api/v1/projects/" + projectId + "/api-definitions", null, session);
            assertEquals(200, activeDefinitions.status());
            assertFalse(activeDefinitions.body().contains(definitionId));
            Response allDefinitions = request(baseUrl, "GET",
                    "/api/v1/projects/" + projectId + "/api-definitions?includeArchived=true", null, session);
            assertEquals(200, allDefinitions.status());
            assertTrue(allDefinitions.body().contains(definitionId));

            Response projectArchive = request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/archive",
                    "{\"revision\":0}", session);
            assertEquals(200, projectArchive.status());
            Response archivedProjectDefinition = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/api-definitions",
                    "{\"name\":\"blocked-by-project\",\"method\":\"GET\","
                            + "\"urlTemplate\":\"/blocked\",\"requestSpec\":" + noneRequestSpec() + "}", session);
            assertEquals(409, archivedProjectDefinition.status());
            assertTrue(archivedProjectDefinition.body().contains("PROJECT_ARCHIVED"));
        }
    }

    private static List<InvalidDefinition> invalidDefinitions() {
        return List.of(
                new InvalidDefinition("invalid-url", "GET",
                        "https://user:pass@example.test/F105_URL_SENTINEL#fragment", noneRequestSpec(),
                        "urlTemplate", "F105_URL_SENTINEL"),
                new InvalidDefinition("invalid-path-mismatch", "GET",
                        "/orders/{orderId}?sentinel=F105_PATH_SENTINEL", noneRequestSpec(),
                        "requestSpec.pathParams", "F105_PATH_SENTINEL"),
                new InvalidDefinition("duplicate-query", "GET", "/orders",
                        "{\"pathParams\":[],\"query\":[{\"name\":\"q\",\"value\":\"1\",\"enabled\":true},"
                                + "{\"name\":\"q\",\"value\":\"F105_QUERY_SENTINEL\",\"enabled\":true}],"
                                + "\"headers\":[],\"body\":{\"type\":\"NONE\"}}",
                        "requestSpec.query[1].name", "F105_QUERY_SENTINEL"),
                new InvalidDefinition("duplicate-header", "GET", "/orders",
                        "{\"pathParams\":[],\"query\":[],\"headers\":[{\"name\":\"X-Tenant\","
                                + "\"value\":\"one\",\"enabled\":true},{\"name\":\"x-tenant\","
                                + "\"value\":\"F105_HEADER_SENTINEL\",\"enabled\":true}],"
                                + "\"body\":{\"type\":\"NONE\"}}",
                        "requestSpec.headers[1].name", "F105_HEADER_SENTINEL"),
                new InvalidDefinition("invalid-token", "GET", "/orders",
                        "{\"pathParams\":[],\"query\":[{\"name\":\"q\","
                                + "\"value\":\"${F105_TOKEN bad}\",\"enabled\":true}],"
                                + "\"headers\":[],\"body\":{\"type\":\"NONE\"}}",
                        "requestSpec.query[0].value", "F105_TOKEN bad"),
                new InvalidDefinition("plain-sensitive-header", "GET", "/orders",
                        "{\"pathParams\":[],\"query\":[],\"headers\":[{\"name\":\"X-Api-Key\",\"value\":\"F105_PLAIN_API_KEY\",\"enabled\":true}],\"body\":{\"type\":\"NONE\"}}",
                        "requestSpec.headers[0].value", "F105_PLAIN_API_KEY"),
                new InvalidDefinition("plain-sensitive-cookie", "GET", "/orders",
                        "{\"pathParams\":[],\"query\":[],\"headers\":[],\"cookies\":[{\"name\":\"session\",\"value\":\"F105_PLAIN_COOKIE\",\"enabled\":true}],\"body\":{\"type\":\"NONE\"}}",
                        "requestSpec.cookies[0].value", "F105_PLAIN_COOKIE"),
                new InvalidDefinition("unclosed-token", "GET", "/orders",
                        "{\"pathParams\":[],\"query\":[{\"name\":\"q\","
                                + "\"value\":\"${F105_UNCLOSED\",\"enabled\":true}],"
                                + "\"headers\":[],\"body\":{\"type\":\"NONE\"}}",
                        "requestSpec.query[0].value", "F105_UNCLOSED"),
                new InvalidDefinition("get-json-body", "GET", "/orders",
                        "{\"pathParams\":[],\"query\":[],\"headers\":[],"
                                + "\"body\":{\"type\":\"JSON\",\"value\":\"F105_GET_JSON_SENTINEL\"}}",
                        "requestSpec.body.type", "F105_GET_JSON_SENTINEL"),
                new InvalidDefinition("invalid-method", "TRACE", "/orders/F105_METHOD_SENTINEL", noneRequestSpec(),
                        "method", "F105_METHOD_SENTINEL"));
    }

    private static List<InvalidCase> invalidCases(String validCaseSpec) {
        return List.of(
                new InvalidCase("invalid-variables-F105_VARIABLES_SENTINEL", validCaseSpec, "[]", "[]",
                        "variables", "F105_VARIABLES_SENTINEL"),
                new InvalidCase("invalid-status-F105_STATUS_SENTINEL", validCaseSpec, "{}",
                        "[{\"type\":\"STATUS\",\"operator\":\"EQUALS\",\"expected\":99}]",
                        "assertions[0]", "F105_STATUS_SENTINEL"),
                new InvalidCase("invalid-json-path", validCaseSpec, "{}",
                        "[{\"type\":\"JSON_PATH\",\"expression\":\"data.F105_JSON_PATH_SENTINEL\","
                                + "\"operator\":\"EXISTS\"}]",
                        "assertions[0].expression", "F105_JSON_PATH_SENTINEL"),
                new InvalidCase("exists-with-expected", validCaseSpec, "{}",
                        "[{\"type\":\"JSON_PATH\",\"expression\":\"$.data.id\","
                                + "\"operator\":\"EXISTS\",\"expected\":\"F105_EXISTS_SENTINEL\"}]",
                        "assertions[0].expected", "F105_EXISTS_SENTINEL"),
                new InvalidCase("invalid-extractor-type", validCaseSpec.replace("JSON_PATH", "SCRIPT"), "{}", "[]",
                        "caseSpec.extractors[0].type", "F105_EXTRACTOR_TYPE_SENTINEL"),
                new InvalidCase("invalid-extractor-expression",
                        validCaseSpec.replace("$.data.token", "data.F105_EXTRACTOR_EXPRESSION_SENTINEL"), "{}", "[]",
                        "caseSpec.extractors[0].expression", "F105_EXTRACTOR_EXPRESSION_SENTINEL"));
    }

    private void assertValidationError(Response response, String fieldPath, String sentinel) throws Exception {
        assertEquals(400, response.status());
        JsonNode error = json.readTree(response.body());
        assertEquals("VALIDATION_FAILED", error.path("code").asText());
        JsonNode fieldErrors = error.path("details").path("fieldErrors");
        assertTrue(fieldErrors.isArray());
        boolean found = false;
        for (JsonNode fieldError : fieldErrors) {
            if (fieldPath.equals(fieldError.path("path").asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "missing fieldErrors path: " + fieldPath + " in " + response.body());
        assertFalse(response.body().contains(sentinel));
    }

    private void assertInvalidRequestNoEcho(Response response, String sentinel) throws Exception {
        assertEquals(400, response.status());
        assertEquals("INVALID_REQUEST", json.readTree(response.body()).path("code").asText());
        assertFalse(response.body().contains(sentinel));
    }

    private void assertChildIncompatible(Response response, String sentinel) throws Exception {
        assertEquals(409, response.status());
        JsonNode error = json.readTree(response.body());
        assertEquals("CHILD_CASE_INCOMPATIBLE", error.path("code").asText());
        assertTrue(error.path("details").has("caseId"));
        JsonNode fieldErrors = error.path("details").path("fieldErrors");
        assertTrue(fieldErrors.isArray());
        assertTrue(fieldErrors.size() > 0);
        assertTrue(fieldErrors.get(0).has("path"));
        assertTrue(fieldErrors.get(0).has("code"));
        assertFalse(response.body().contains(sentinel));
    }

    private static String requestSpecWithoutQuery() {
        return "{\"pathParams\":[{\"name\":\"orderId\",\"value\":\"${orderId}\"}],"
                + "\"query\":[],"
                + "\"headers\":[{\"name\":\"X-Tenant\",\"value\":\"${tenant}\",\"enabled\":true}],"
                + "\"body\":{\"type\":\"JSON\",\"value\":{\"orderId\":\"${orderId}\","
                + "\"items\":[1,true,null]}}}";
    }

    private static String getNoneRequestSpec() {
        return "{\"pathParams\":[{\"name\":\"orderId\",\"value\":\"${orderId}\"}],"
                + "\"query\":[{\"name\":\"verbose\",\"value\":\"true\",\"enabled\":true}],"
                + "\"headers\":[{\"name\":\"X-Tenant\",\"value\":\"${tenant}\",\"enabled\":true}],"
                + "\"body\":{\"type\":\"NONE\"}}";
    }

    private static String noneRequestSpec() {
        return "{\"pathParams\":[],\"query\":[],\"headers\":[],\"body\":{\"type\":\"NONE\"}}";
    }

    private static String emptyCaseSpec() {
        return "{\"pathParams\":{},\"query\":{},\"headers\":{},\"body\":{\"type\":\"NONE\"}}";
    }

    private static ConfigurableApplicationContext startApplication() {
        Properties properties = new Properties();
        properties.setProperty("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        properties.setProperty("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        properties.setProperty("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        properties.setProperty("AUTOTEST_ADMIN_USERNAME", "owner@example.com");
        properties.setProperty("AUTOTEST_ADMIN_PASSWORD", ADMIN_PASSWORD);
        properties.setProperty("AUTOTEST_MASTER_KEY", MASTER_KEY);
        properties.setProperty("server.port", "0");
        return new SpringApplicationBuilder(PlatformApiApplication.class).properties(properties).run();
    }

    private static String baseUrl(ConfigurableApplicationContext context) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return "http://127.0.0.1:" + port;
    }

    private Response request(String baseUrl, String method, String path, String body, Session session) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "application/json");
        if (session != null) {
            builder.header("Cookie", session.sessionCookie() + "; " + session.csrfCookie());
            builder.header("X-XSRF-TOKEN", session.csrfCookie().substring(session.csrfCookie().indexOf('=') + 1));
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        builder.header("Content-Type", "application/json");
        builder.method(method, publisher);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), response.headers().allValues("Set-Cookie"));
    }

    private static String cookie(Response response, String name) {
        return response.setCookies().stream()
                .map(value -> value.substring(0, value.indexOf(';')))
                .filter(value -> value.startsWith(name + "="))
                .findFirst().orElseThrow();
    }

    private String field(Response response, String field) throws Exception {
        return json.readTree(response.body()).get(field).asText();
    }

    private int intField(Response response, String field) throws Exception {
        return json.readTree(response.body()).get(field).asInt();
    }

    private boolean boolField(Response response, String field) throws Exception {
        return json.readTree(response.body()).get(field).asBoolean();
    }

    private record Session(String sessionCookie, String csrfCookie) {
    }

    private record InvalidDefinition(String name, String method, String urlTemplate, String requestSpec,
                                     String fieldPath, String sentinel) {
    }

    private record InvalidCase(String name, String caseSpec, String variables, String assertions,
                               String fieldPath, String sentinel) {
    }

    private record Response(int status, String body, List<String> setCookies) {
    }
}
