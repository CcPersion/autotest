package com.autotest.platform.project;

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
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class ProjectModulePostgresqlIntegrationTest {

    private static final String ADMIN_PASSWORD = "F1-03-password-123!";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("autotest")
            .withUsername("autotest")
            .withPassword("test-only-password");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void managesProjectsAndHierarchicalModulesWithOptimisticRules() throws Exception {
        try (ConfigurableApplicationContext context = startApplication()) {
            String baseUrl = baseUrl(context);
            Response login = request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\"owner@example.com\",\"password\":\"" + ADMIN_PASSWORD + "\"}", null);
            assertEquals(200, login.status());
            Session session = new Session(sessionCookie(login), csrfCookie(login));

            Response projectOne = request(baseUrl, "POST", "/api/v1/projects",
                    "{\"name\":\" Checkout \",\"description\":\"first\","
                            + "\"targetAllowlist\":[\"api.example.test\",\"*.internal.test\"]}", session);
            assertEquals(201, projectOne.status());
            String projectOneId = field(projectOne.body(), "id");
            assertEquals(0, intField(projectOne.body(), "revision"));
            assertEquals(2, json.readTree(projectOne.body()).path("targetAllowlist").size());

            Response projectTwo = request(baseUrl, "POST", "/api/v1/projects",
                    "{\"name\":\"Other\"}", session);
            assertEquals(201, projectTwo.status());
            String projectTwoId = field(projectTwo.body(), "id");

            Response foreignModule = request(baseUrl, "POST", "/api/v1/projects/" + projectTwoId + "/modules",
                    "{\"name\":\"Foreign Module\"}", session);
            assertEquals(201, foreignModule.status());
            String foreignModuleId = field(foreignModule.body(), "id");

            Response duplicateProject = request(baseUrl, "POST", "/api/v1/projects",
                    "{\"name\":\"checkout\"}", session);
            assertEquals(409, duplicateProject.status());
            assertTrue(duplicateProject.body().contains("NAME_CONFLICT"));

            Response invalidAllowlist = request(baseUrl, "POST", "/api/v1/projects",
                    "{\"name\":\"Bad target policy\",\"targetAllowlist\":[\"https://example.test/path\"]}", session);
            assertEquals(400, invalidAllowlist.status());
            assertTrue(invalidAllowlist.body().contains("VALIDATION_FAILED"));

            Response projectUpdate = request(baseUrl, "PUT", "/api/v1/projects/" + projectOneId,
                    "{\"name\":\"Checkout API\",\"description\":\"changed\","
                            + "\"targetAllowlist\":[\"api.example.test\"],\"revision\":0}", session);
            assertEquals(200, projectUpdate.status());
            assertEquals(1, intField(projectUpdate.body(), "revision"));
            assertEquals("api.example.test", json.readTree(projectUpdate.body()).path("targetAllowlist").get(0).asText());

            Response staleProjectUpdate = request(baseUrl, "PUT", "/api/v1/projects/" + projectOneId,
                    "{\"name\":\"stale\",\"revision\":0}", session);
            assertEquals(409, staleProjectUpdate.status());
            assertTrue(staleProjectUpdate.body().contains("REVISION_CONFLICT"));

            Response projectArchive = request(baseUrl, "POST", "/api/v1/projects/" + projectOneId + "/archive",
                    "{\"revision\":1}", session);
            assertEquals(200, projectArchive.status());
            assertTrue(boolField(projectArchive.body(), "archived"));

            Response archivedProjectWrite = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/modules",
                    "{\"name\":\"root\"}", session);
            assertEquals(409, archivedProjectWrite.status());
            assertTrue(archivedProjectWrite.body().contains("PROJECT_ARCHIVED"));

            Response projectRestore = request(baseUrl, "POST", "/api/v1/projects/" + projectOneId + "/restore",
                    "{\"revision\":2}", session);
            assertEquals(200, projectRestore.status());
            assertFalse(boolField(projectRestore.body(), "archived"));

            Response rootA = request(baseUrl, "POST", "/api/v1/projects/" + projectOneId + "/modules",
                    "{\"name\":\"Root A\",\"position\":0}", session);
            assertEquals(201, rootA.status());
            String rootAId = field(rootA.body(), "id");

            Response rootB = request(baseUrl, "POST", "/api/v1/projects/" + projectOneId + "/modules",
                    "{\"name\":\"Root B\",\"position\":0}", session);
            assertEquals(201, rootB.status());
            String rootBId = field(rootB.body(), "id");

            Response child = request(baseUrl, "POST", "/api/v1/projects/" + projectOneId + "/modules",
                    "{\"name\":\"Child\",\"parentId\":\"" + rootAId + "\",\"position\":0}", session);
            assertEquals(201, child.status());
            String childId = field(child.body(), "id");

            Response grandchild = request(baseUrl, "POST", "/api/v1/projects/" + projectOneId + "/modules",
                    "{\"name\":\"Grandchild\",\"parentId\":\"" + childId + "\"}", session);
            assertEquals(201, grandchild.status());
            String grandchildId = field(grandchild.body(), "id");

            Response duplicateModule = request(baseUrl, "POST", "/api/v1/projects/" + projectOneId + "/modules",
                    "{\"name\":\"root b\"}", session);
            assertEquals(409, duplicateModule.status());
            assertTrue(duplicateModule.body().contains("NAME_CONFLICT"));

            Response crossProjectParent = request(baseUrl, "POST", "/api/v1/projects/" + projectOneId + "/modules",
                    "{\"name\":\"foreign child\",\"parentId\":\"" + foreignModuleId + "\"}", session);
            assertEquals(404, crossProjectParent.status());

            Response crossProjectPath = request(baseUrl, "PUT",
                    "/api/v1/projects/" + projectOneId + "/modules/" + foreignModuleId,
                    "{\"name\":\"should not access\",\"revision\":0}", session);
            assertEquals(404, crossProjectPath.status());

            Response tree = request(baseUrl, "GET", "/api/v1/projects/" + projectOneId + "/modules/tree", null, session);
            assertEquals(200, tree.status());
            JsonNode roots = json.readTree(tree.body());
            assertEquals(rootBId, roots.get(0).get("id").asText());
            assertEquals(rootAId, roots.get(1).get("id").asText());
            assertEquals(childId, roots.get(1).get("children").get(0).get("id").asText());
            assertEquals(grandchildId, roots.get(1).get("children").get(0).get("children").get(0).get("id").asText());

            Response sameParentModule = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/modules",
                    "{\"name\":\"Same Parent\",\"parentId\":\"" + rootAId + "\"}", session);
            assertEquals(201, sameParentModule.status());
            String sameParentModuleId = field(sameParentModule.body(), "id");
            Response sameParentMove = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/modules/" + sameParentModuleId + "/move",
                    "{\"parentId\":\"" + rootAId + "\",\"position\":0,\"revision\":0}", session);
            assertEquals(200, sameParentMove.status());
            assertEquals(1, intField(sameParentMove.body(), "revision"));
            assertEquals(0, intField(sameParentMove.body(), "sortOrder"));

            Response moveSource = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/modules",
                    "{\"name\":\"Move Conflict\",\"parentId\":\"" + rootAId + "\"}", session);
            assertEquals(201, moveSource.status());
            String moveSourceId = field(moveSource.body(), "id");
            Response moveTarget = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/modules",
                    "{\"name\":\"Move Conflict\",\"parentId\":\"" + rootBId + "\"}", session);
            assertEquals(201, moveTarget.status());
            Response duplicateMove = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/modules/" + moveSourceId + "/move",
                    "{\"parentId\":\"" + rootBId + "\",\"position\":0,\"revision\":0}", session);
            assertEquals(409, duplicateMove.status());
            assertTrue(duplicateMove.body().contains("NAME_CONFLICT"));

            Response rename = request(baseUrl, "PUT",
                    "/api/v1/projects/" + projectOneId + "/modules/" + childId,
                    "{\"name\":\"Renamed Child\",\"revision\":0}", session);
            assertEquals(200, rename.status());
            assertEquals(1, intField(rename.body(), "revision"));

            Response staleRename = request(baseUrl, "PUT",
                    "/api/v1/projects/" + projectOneId + "/modules/" + childId,
                    "{\"name\":\"stale\",\"revision\":0}", session);
            assertEquals(409, staleRename.status());
            assertTrue(staleRename.body().contains("REVISION_CONFLICT"));

            Response nonEmptyDelete = request(baseUrl, "DELETE",
                    "/api/v1/projects/" + projectOneId + "/modules/" + childId + "?revision=1", null, session);
            assertEquals(409, nonEmptyDelete.status());
            assertTrue(nonEmptyDelete.body().contains("MODULE_NOT_EMPTY"));
            assertTrue(nonEmptyDelete.body().contains("childCount"));
            assertTrue(nonEmptyDelete.body().contains("apiCount"));

            Response moveGrandchild = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/modules/" + grandchildId + "/move",
                    "{\"position\":0,\"revision\":0}", session);
            assertEquals(200, moveGrandchild.status());
            assertEquals(1, intField(moveGrandchild.body(), "revision"));

            Response cycle = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/modules/" + rootAId + "/move",
                    "{\"parentId\":\"" + childId + "\",\"position\":0,\"revision\":0}", session);
            assertEquals(409, cycle.status());
            assertTrue(cycle.body().contains("MODULE_CYCLE"));

            Response emptyDelete = request(baseUrl, "DELETE",
                    "/api/v1/projects/" + projectOneId + "/modules/" + childId + "?revision=1", null, session);
            assertEquals(204, emptyDelete.status());

            Response movedTree = request(baseUrl, "GET", "/api/v1/projects/" + projectOneId + "/modules/tree", null, session);
            assertEquals(200, movedTree.status());
            JsonNode movedRoots = json.readTree(movedTree.body());
            assertFalse(movedTree.body().contains(childId));
            assertEquals(grandchildId, movedRoots.get(0).get("id").asText());
            assertEquals(0, movedRoots.get(0).get("sortOrder").asInt());
            assertEquals(1, movedRoots.get(0).get("revision").asInt());
            assertEquals(1, movedRoots.get(1).get("sortOrder").asInt());
            assertEquals(2, movedRoots.get(2).get("sortOrder").asInt());

            Response projectNotFound = request(baseUrl, "GET", "/api/v1/projects/00000000-0000-0000-0000-000000000000", null, session);
            assertEquals(404, projectNotFound.status());
            Response moduleNotFound = request(baseUrl, "GET",
                    "/api/v1/projects/" + projectOneId + "/modules/" + rootBId + "/missing", null, session);
            assertEquals(404, moduleNotFound.status());
        }
    }

    private static ConfigurableApplicationContext startApplication() {
        Properties properties = new Properties();
        properties.setProperty("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        properties.setProperty("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        properties.setProperty("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        properties.setProperty("AUTOTEST_ADMIN_USERNAME", "owner@example.com");
        properties.setProperty("AUTOTEST_ADMIN_PASSWORD", ADMIN_PASSWORD);
        properties.setProperty("AUTOTEST_MASTER_KEY", "RjEwNC10ZXN0LW1hc3Rlci1rZXktMzItYnl0ZXMhISE=");
        properties.setProperty("server.port", "0");
        return new SpringApplicationBuilder(PlatformApiApplication.class).properties(properties).run();
    }

    private static String baseUrl(ConfigurableApplicationContext context) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return "http://127.0.0.1:" + port;
    }

    private Response request(String baseUrl, String method, String path, String body, Session session) throws Exception {
        return request(baseUrl, method, path, body, session, session);
    }

    private Response request(String baseUrl, String method, String path, String body,
                             Session cookies, Session csrfSource) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "application/json");
        if (cookies != null) {
            builder.header("Cookie", cookies.sessionCookie + "; " + cookies.csrfCookie);
        }
        if (csrfSource != null) {
            builder.header("X-XSRF-TOKEN", csrfSource.csrfCookie.substring(csrfSource.csrfCookie.indexOf('=') + 1));
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        builder.header("Content-Type", "application/json");
        builder.method(method, publisher);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), response.headers().allValues("Set-Cookie"));
    }

    private static String sessionCookie(Response response) {
        return response.setCookies().stream()
                .map(value -> value.substring(0, value.indexOf(';')))
                .filter(value -> value.startsWith("JSESSIONID="))
                .findFirst().orElseThrow();
    }

    private static String csrfCookie(Response response) {
        return response.setCookies().stream()
                .map(value -> value.substring(0, value.indexOf(';')))
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .findFirst().orElseThrow();
    }

    private String field(String body, String field) throws Exception {
        return json.readTree(body).get(field).asText();
    }

    private int intField(String body, String field) throws Exception {
        return json.readTree(body).get(field).asInt();
    }

    private boolean boolField(String body, String field) throws Exception {
        return json.readTree(body).get(field).asBoolean();
    }

    private record Session(String sessionCookie, String csrfCookie) {
    }

    private record Response(int status, String body, List<String> setCookies) {
    }
}
