package com.autotest.platform.run;

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
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class RunPostgresqlIntegrationTest {

    private static final String ADMIN_PASSWORD = "F1-08-password-123!";
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
    void createsIdempotentRunReadsItAndCancelsPendingRun() throws Exception {
        try (ConfigurableApplicationContext context = startApplication()) {
            String baseUrl = baseUrl(context);
            Response login = request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\"owner@example.com\",\"password\":\"" + ADMIN_PASSWORD + "\"}", null);
            assertEquals(200, login.status());
            Session session = new Session(cookie(login, "JSESSIONID"), cookie(login, "XSRF-TOKEN"));

            String projectId = field(request(baseUrl, "POST", "/api/v1/projects",
                    "{\"name\":\"F1-08 project\"}", session), "id");
            String environmentId = field(request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/environments",
                    "{\"name\":\"local\",\"baseUrl\":\"http://127.0.0.1:18081\",\"variables\":{}}",
                    session), "id");
            String idempotencyKey = "f1-08-idempotency-" + UUID.randomUUID();
            String body = "{\"environmentId\":\"" + environmentId + "\","
                    + "\"targetType\":\"API_CASE\",\"targetId\":\"" + UUID.randomUUID() + "\","
                    + "\"idempotencyKey\":\"" + idempotencyKey + "\","
                    + "\"executionPlan\":{\"planId\":\"f1-08-plan\",\"jmeterVersion\":\"5.6.3\","
                    + "\"assetContent\":{},\"variables\":{},\"secretRefs\":[],\"fileChecksums\":{}}}";

            Response created = request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/runs", body, session);
            assertEquals(201, created.status());
            String runId = field(created, "id");
            assertEquals("PENDING", field(created, "status"));

            Response repeated = request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/runs", body, session);
            assertEquals(200, repeated.status());
            assertEquals(runId, field(repeated, "id"));

            Response read = request(baseUrl, "GET", "/api/v1/projects/" + projectId + "/runs/" + runId,
                    null, session);
            assertEquals(200, read.status());
            assertEquals(runId, field(read, "id"));

            Response recent = request(baseUrl, "GET", "/api/v1/projects/" + projectId + "/runs?limit=5",
                    null, session);
            assertEquals(200, recent.status());
            assertEquals(runId, json.readTree(recent.body()).get(0).path("id").asText());

            Response canceled = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/runs/" + runId + "/cancel", "{}", session);
            assertEquals(200, canceled.status());
            assertEquals("CANCELED", field(canceled, "status"));
            assertNotEquals("", field(canceled, "finishedAt"));
        }
    }

    @Test
    void freezesTargetAllowlistInsidePersistedRunPlan() throws Exception {
        try (ConfigurableApplicationContext context = startApplication()) {
            String baseUrl = baseUrl(context);
            Response login = request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\"owner@example.com\",\"password\":\"" + ADMIN_PASSWORD + "\"}", null);
            assertEquals(200, login.status());
            Session session = new Session(cookie(login, "JSESSIONID"), cookie(login, "XSRF-TOKEN"));

            String projectId = field(request(baseUrl, "POST", "/api/v1/projects",
                    "{\"name\":\"Frozen target policy " + UUID.randomUUID()
                            + "\",\"targetAllowlist\":[\"127.0.0.1:18081\"]}", session), "id");
            String environmentId = field(request(baseUrl, "POST",
                    "/api/v1/projects/" + projectId + "/environments",
                    "{\"name\":\"local\",\"baseUrl\":\"http://127.0.0.1:18081\",\"variables\":{}}",
                    session), "id");
            String runBody = "{\"environmentId\":\"" + environmentId + "\","
                    + "\"targetType\":\"API_CASE\",\"targetId\":\"" + UUID.randomUUID() + "\","
                    + "\"idempotencyKey\":\"f4-04-freeze-" + UUID.randomUUID() + "\","
                    + "\"executionPlan\":{\"planId\":\"freeze-plan\",\"jmeterVersion\":\"5.6.3\","
                    + "\"baseUrl\":\"http://127.0.0.1:18081\",\"method\":\"GET\","
                    + "\"urlTemplate\":\"/health\",\"body\":{\"type\":\"NONE\"}}}";

            Response created = request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/runs", runBody, session);
            assertEquals(201, created.status());
            String runId = field(created, "id");
            JsonNode storedPlan = json.readTree(created.body()).path("executionPlan");
            assertTrue(storedPlan.path("targetPolicyRequired").asBoolean());
            assertEquals("127.0.0.1:18081", storedPlan.path("targetAllowlist").get(0).asText());

            Response project = request(baseUrl, "GET", "/api/v1/projects/" + projectId, null, session);
            int revision = json.readTree(project.body()).path("revision").asInt();
            Response update = request(baseUrl, "PUT", "/api/v1/projects/" + projectId,
                    "{\"name\":\"Frozen target policy updated\",\"description\":\"\","
                            + "\"targetAllowlist\":[\"api.example.test\"],\"revision\":" + revision + "}", session);
            assertEquals(200, update.status());

            Response read = request(baseUrl, "GET", "/api/v1/projects/" + projectId + "/runs/" + runId,
                    null, session);
            assertEquals(200, read.status());
            JsonNode rereadPlan = json.readTree(read.body()).path("executionPlan");
            assertEquals("127.0.0.1:18081", rereadPlan.path("targetAllowlist").get(0).asText());
            assertEquals("http://127.0.0.1:18081", rereadPlan.path("baseUrl").asText());
        }
    }

    private ConfigurableApplicationContext startApplication() {
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

    private String baseUrl(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private Response request(String baseUrl, String method, String path, String body, Session session) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "application/json");
        if (session != null) {
            builder.header("Cookie", session.sessionCookie() + "; " + session.csrfCookie());
            if (!method.equals("GET")) {
                builder.header("X-XSRF-TOKEN", session.csrfCookie().substring(session.csrfCookie().indexOf('=') + 1));
            }
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), response.headers().allValues("set-cookie"));
    }

    private String field(Response response, String name) throws Exception {
        JsonNode node = json.readTree(response.body()).get(name);
        return node == null || node.isNull() ? "" : node.asText();
    }

    private String cookie(Response response, String name) {
        return response.cookies().stream()
                .map(value -> value.substring(0, value.indexOf(';')))
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .orElseThrow();
    }

    private record Session(String sessionCookie, String csrfCookie) {
    }

    private record Response(int status, String body, java.util.List<String> cookies) {
    }
}
