package com.autotest.platform.report;

import com.autotest.platform.PlatformApiApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class F402ReportRetentionPostgresqlIntegrationTest {

    private static final String PASSWORD = "F4-02-password-123!";
    private static final String CALLBACK_TOKEN = "f402-runner-token";
    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(
            "F402-test-master-key-32-bytes!!!".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("autotest").withUsername("autotest").withPassword("test-only-password");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exportsMaskedReportAndCleansOnlyExpiredTerminalRuns() throws Exception {
        try (ConfigurableApplicationContext context = startApplication()) {
            String base = baseUrl(context);
            Response login = request(base, "POST", "/api/v1/auth/login",
                    "{\"username\":\"owner@example.com\",\"password\":\"" + PASSWORD + "\"}", null, null);
            Session session = new Session(cookie(login, "JSESSIONID"), cookie(login, "XSRF-TOKEN"));
            String project = field(request(base, "POST", "/api/v1/projects", "{\"name\":\"f402-project\"}", session, null), "id");
            String environment = field(request(base, "POST", "/api/v1/projects/" + project + "/environments",
                    "{\"name\":\"local\",\"baseUrl\":\"http://127.0.0.1\",\"variables\":{}}", session, null), "id");
            String oldRun = createRun(base, project, environment, session, "f402-old");
            String activeRun = createRun(base, project, environment, session, "f402-active");
            postStep(base, oldRun, session, "step-old");
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("UPDATE runs SET status = 'PASSED', finished_at = now(), created_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(172800)), UUID.fromString(oldRun));

            Response html = request(base, "GET", "/api/v1/projects/" + project + "/runs/" + oldRun + "/exports/html", null, session, null);
            Response allure = request(base, "GET", "/api/v1/projects/" + project + "/runs/" + oldRun + "/exports/allure", null, session, null);
            assertEquals(200, html.status(), html.body());
            assertTrue(html.body().contains("token=***"));
            assertFalse(html.body().contains("plain-secret"));
            assertEquals(200, allure.status(), allure.body());
            assertTrue(allure.headers().stream().anyMatch(value -> value.contains("application/zip")));
            try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(allure.bytes()))) {
                assertTrue(zip.getNextEntry() != null);
            }

            Response setting = request(base, "PUT", "/api/v1/projects/" + project + "/retention",
                    "{\"retentionDays\":1,\"revision\":0}", session, null);
            assertEquals(200, setting.status());
            Response cleanup = request(base, "POST", "/api/v1/projects/" + project + "/retention/cleanup", "{}", session, null);
            assertEquals(200, cleanup.status());
            assertEquals(1, json.readTree(cleanup.body()).get("deletedRuns").asInt());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE id = ?", Integer.class, UUID.fromString(oldRun)));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE id = ?", Integer.class, UUID.fromString(activeRun)));
        }
    }

    private String createRun(String base, String project, String environment, Session session, String key) throws Exception {
        return field(request(base, "POST", "/api/v1/projects/" + project + "/runs",
                "{\"environmentId\":\"" + environment + "\",\"targetType\":\"API_CASE\",\"targetId\":\""
                        + UUID.randomUUID() + "\",\"idempotencyKey\":\"" + key + "\",\"executionPlan\":{\"planId\":\""
                        + key + "\",\"jmeterVersion\":\"5.6.3\",\"assetContent\":{},\"variables\":{},\"secretRefs\":[],\"fileChecksums\":{}}}", session, null), "id");
    }

    private void postStep(String base, String run, Session session, String key) throws Exception {
        String body = "{\"stepId\":\"" + UUID.randomUUID() + "\",\"resultKey\":\"" + key + "\",\"sequenceNo\":0,\"status\":\"FAILED\",\"durationMs\":1,"
                + "\"requestSummary\":{\"url\":\"http://x/?token=plain-secret\"},\"responseSummary\":{\"authorization\":\"Bearer plain-secret\"},"
                + "\"assertions\":[],\"extractions\":[],\"errorSummary\":{\"message\":\"failed\"}}";
        assertEquals(200, request(base, "POST", "/api/v1/internal/runs/" + run + "/step-results", body, null, CALLBACK_TOKEN).status());
    }

    private ConfigurableApplicationContext startApplication() {
        Properties properties = new Properties();
        properties.setProperty("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        properties.setProperty("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        properties.setProperty("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        properties.setProperty("AUTOTEST_ADMIN_USERNAME", "owner@example.com");
        properties.setProperty("AUTOTEST_ADMIN_PASSWORD", PASSWORD);
        properties.setProperty("AUTOTEST_MASTER_KEY", MASTER_KEY);
        properties.setProperty("AUTOTEST_RUNNER_CALLBACK_TOKEN", CALLBACK_TOKEN);
        properties.setProperty("autotest.runner.callback-token", CALLBACK_TOKEN);
        properties.setProperty("server.port", "0");
        return new SpringApplicationBuilder(PlatformApiApplication.class).properties(properties).run();
    }

    private String baseUrl(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private Response request(String base, String method, String path, String body, Session session, String runnerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept", path.contains("/exports/") ? "*/*" : "application/json");
        if (session != null) {
            builder.header("Cookie", session.sessionCookie() + "; " + session.csrfCookie());
            if (!method.equals("GET")) builder.header("X-XSRF-TOKEN", session.csrfCookie().substring(session.csrfCookie().indexOf('=') + 1));
        }
        if (runnerToken != null) builder.header("X-Runner-Token", runnerToken);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Response(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8), response.body(), response.headers().allValues("content-type"), response.headers().allValues("set-cookie"));
    }

    private String field(Response response, String name) throws Exception {
        return json.readTree(response.body()).path(name).asText();
    }

    private String cookie(Response response, String name) {
        return response.cookies().stream().map(value -> value.substring(0, value.indexOf(';')))
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
    }

    private record Session(String sessionCookie, String csrfCookie) {}
    private record Response(int status, String body, byte[] bytes, List<String> headers, List<String> cookies) {}
}
