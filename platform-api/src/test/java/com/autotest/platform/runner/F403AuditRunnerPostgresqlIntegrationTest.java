package com.autotest.platform.runner;

import com.autotest.platform.PlatformApiApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.Test;

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
class F403AuditRunnerPostgresqlIntegrationTest {
    private static final String PASSWORD = "F4-03-password-123!";
    private static final String TOKEN = "f403-runner-token";
    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(
            "F403-test-master-key-32-bytes!!!".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("autotest").withUsername("autotest").withPassword("test-only-password");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void persistsMaskedAuditAndReportsRunnerOnlineThenOffline() throws Exception {
        try (ConfigurableApplicationContext context = startApplication()) {
            String base = baseUrl(context);
            Response login = request(base, "POST", "/api/v1/auth/login",
                    "{\"username\":\"owner@example.com\",\"password\":\"" + PASSWORD + "\"}", null, null);
            assertEquals(200, login.status());
            Session session = new Session(cookie(login, "JSESSIONID"), cookie(login, "XSRF-TOKEN"));
            String project = field(request(base, "POST", "/api/v1/projects", "{\"name\":\"f403-project\"}", session, null), "id");
            Response secret = request(base, "POST", "/api/v1/projects/" + project + "/secrets",
                    "{\"name\":\"api-token\",\"value\":\"plain-secret\"}", session, null);
            assertEquals(201, secret.status(), secret.body());
            String trace = secret.header("x-trace-id");

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'LOGIN_SUCCEEDED'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'SECRET_CREATED'", Integer.class));
            String metadata = jdbc.queryForObject("SELECT metadata_json::text FROM audit_events WHERE action = 'SECRET_CREATED'", String.class);
            assertFalse(metadata.contains("plain-secret"));
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE trace_id = ?", Integer.class, trace) >= 1);

            UUID runnerId = UUID.randomUUID();
            Response bad = request(base, "POST", "/api/v1/internal/runners/" + runnerId + "/heartbeat",
                    "{\"runnerVersion\":\"0.1.0\",\"jmeterVersion\":\"5.6.3\",\"queueDepth\":1}", null, "wrong-token");
            assertEquals(401, bad.status());
            Response heartbeat = request(base, "POST", "/api/v1/internal/runners/" + runnerId + "/heartbeat",
                    "{\"runnerVersion\":\"0.1.0\",\"jmeterVersion\":\"5.6.3\",\"queueDepth\":1}", null, TOKEN);
            assertEquals(204, heartbeat.status(), heartbeat.body());
            Response online = request(base, "GET", "/api/v1/runners/status", null, session, null);
            assertEquals(200, online.status(), online.body());
            assertEquals("ONLINE", json.readTree(online.body()).get(0).path("status").asText());
            jdbc.update("UPDATE runner_status SET last_seen_at = now() - interval '1 minute' WHERE runner_id = ?", runnerId);
            Response offline = request(base, "GET", "/api/v1/runners/status", null, session, null);
            assertEquals("OFFLINE", json.readTree(offline.body()).get(0).path("status").asText());
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
        properties.setProperty("autotest.runner.callback-token", TOKEN);
        properties.setProperty("server.port", "0");
        return new SpringApplicationBuilder(PlatformApiApplication.class).properties(properties).run();
    }

    private String baseUrl(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private Response request(String base, String method, String path, String body, Session session, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).header("Accept", "application/json");
        if (session != null) {
            builder.header("Cookie", session.sessionCookie() + "; " + session.csrfCookie());
            if (!method.equals("GET")) builder.header("X-XSRF-TOKEN", session.csrfCookie().substring(session.csrfCookie().indexOf('=') + 1));
        }
        if (token != null) builder.header("X-Runner-Token", token);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Response(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8), response.headers().allValues("set-cookie"), response.headers().firstValue("x-trace-id").orElse(""));
    }

    private String field(Response response, String name) throws Exception {
        return json.readTree(response.body()).path(name).asText();
    }

    private String cookie(Response response, String name) {
        return response.cookies().stream().map(value -> value.substring(0, value.indexOf(';')))
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
    }

    private record Session(String sessionCookie, String csrfCookie) {}
    private record Response(int status, String body, List<String> cookies, String traceId) {
        String header(String name) { return "x-trace-id".equalsIgnoreCase(name) ? traceId : ""; }
    }
}
