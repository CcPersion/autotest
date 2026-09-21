package com.autotest.platform.report;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class ReportPostgresqlIntegrationTest {

    private static final String PASSWORD = "F1-09-password-123!";
    private static final String CALLBACK_TOKEN = "runner-callback-test-token";
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
    void callbackIsIdempotentAndReportMasksSensitiveSummary() throws Exception {
        try (ConfigurableApplicationContext context = startApplication()) {
            String base = baseUrl(context);
            Response login = request(base, "POST", "/api/v1/auth/login",
                    "{\"username\":\"owner@example.com\",\"password\":\"" + PASSWORD + "\"}", null, null);
            Session session = new Session(cookie(login, "JSESSIONID"), cookie(login, "XSRF-TOKEN"));
            String project = field(request(base, "POST", "/api/v1/projects", "{\"name\":\"report-project\"}", session, null), "id");
            String environment = field(request(base, "POST", "/api/v1/projects/" + project + "/environments",
                    "{\"name\":\"local\",\"baseUrl\":\"http://127.0.0.1\",\"variables\":{}}", session, null), "id");
            String run = field(request(base, "POST", "/api/v1/projects/" + project + "/runs",
                    "{\"environmentId\":\"" + environment + "\",\"targetType\":\"API_CASE\","
                            + "\"targetId\":\"" + UUID.randomUUID() + "\",\"idempotencyKey\":\"report-1\","
                            + "\"executionPlan\":{\"planId\":\"report-plan\",\"jmeterVersion\":\"5.6.3\","
                            + "\"assetContent\":{},\"variables\":{},\"secretRefs\":[],\"fileChecksums\":{}}}", session, null), "id");
            UUID stepId = UUID.randomUUID();
            String resultBody = "{\"stepId\":\"" + stepId + "\",\"resultKey\":\"step-1\",\"sequenceNo\":0,"
                    + "\"status\":\"FAILED\",\"durationMs\":41,\"requestSummary\":{\"method\":\"POST\",\"url\":\"http://x/p?ACCESS%5FTOKEN=plain-secret&tenant=demo\",\"body\":{\"password\":\"plain-secret\",\"name\":\"qa\"}},"
                    + "\"responseSummary\":{\"statusCode\":500,\"authorization\":\"Bearer plain-secret\",\"body\":\"{\\\"refresh_token\\\":\\\"plain-secret\\\",\\\"ok\\\":false}\"},"
                    + "\"assertions\":[{\"type\":\"JSON_PATH\",\"expression\":\"$.refresh_token\",\"actual\":\"plain-secret\",\"message\":\"actual=plain-secret\",\"passed\":false}],\"extractions\":[{\"variable\":\"profile\"," 
                    + "\"matched\":true,\"value\":{\"roles\":[\"qa\"]}}],\"errorSummary\":{\"message\":\"failure=plain-secret\",\"detail\":\"detail=plain-secret\"}}";

            Response first = request(base, "POST", "/api/v1/internal/runs/" + run + "/step-results", resultBody, null, CALLBACK_TOKEN);
            Response repeated = request(base, "POST", "/api/v1/internal/runs/" + run + "/step-results", resultBody, null, CALLBACK_TOKEN);
            assertEquals(200, first.status());
            assertEquals(field(first, "id"), field(repeated, "id"));

            Response report = request(base, "GET", "/api/v1/projects/" + project + "/runs/" + run + "/report", null, session, null);
            assertEquals(200, report.status());
            JsonNode body = json.readTree(report.body());
            assertEquals(1, body.get("steps").size());
            assertEquals("qa", body.get("steps").get(0).get("extractions").get(0).get("value")
                    .get("roles").get(0).asText());
            assertEquals("POST", body.get("steps").get(0).get("requestSummary").get("method").asText());
            assertEquals("qa", body.get("steps").get(0).get("requestSummary").get("body").get("name").asText());
            assertTrue(report.body().contains("ACCESS%5FTOKEN=***"));
            assertFalse(report.body().contains("plain-secret"));
        }
    }

    private ConfigurableApplicationContext startApplication() {
        Properties properties = new Properties();
        properties.setProperty("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        properties.setProperty("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        properties.setProperty("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        properties.setProperty("AUTOTEST_ADMIN_USERNAME", "owner@example.com");
        properties.setProperty("AUTOTEST_ADMIN_PASSWORD", PASSWORD);
        properties.setProperty("AUTOTEST_MASTER_KEY", MASTER_KEY);
        properties.setProperty("autotest.runner.callback-token", CALLBACK_TOKEN);
        properties.setProperty("server.port", "0");
        return new SpringApplicationBuilder(PlatformApiApplication.class).properties(properties).run();
    }

    private String baseUrl(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private Response request(String base, String method, String path, String body, Session session, String runnerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).header("Accept", "application/json");
        if (session != null) {
            builder.header("Cookie", session.sessionCookie() + "; " + session.csrfCookie());
            if (!method.equals("GET")) {
                builder.header("X-XSRF-TOKEN", session.csrfCookie().substring(session.csrfCookie().indexOf('=') + 1));
            }
        }
        if (runnerToken != null) {
            builder.header("X-Runner-Token", runnerToken);
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
        JsonNode value = json.readTree(response.body()).get(name);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private String cookie(Response response, String name) {
        return response.cookies().stream().map(value -> value.substring(0, value.indexOf(';')))
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
    }

    private record Session(String sessionCookie, String csrfCookie) {
    }

    private record Response(int status, String body, List<String> cookies) {
    }
}
