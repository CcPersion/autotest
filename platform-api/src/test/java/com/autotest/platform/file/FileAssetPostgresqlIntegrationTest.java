package com.autotest.platform.file;

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
class FileAssetPostgresqlIntegrationTest {
    private static final String ADMIN_PASSWORD = "F2-01-file-password-123!";
    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(
            "F201-test-master-key-32-bytes!!!".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("autotest")
            .withUsername("autotest")
            .withPassword("test-only-password");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void uploadsListsAndArchivesWithoutExposingObjectKey() throws Exception {
        try (ConfigurableApplicationContext context = startApplication()) {
            String baseUrl = baseUrl(context);
            Response login = request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\"owner@example.com\",\"password\":\"" + ADMIN_PASSWORD + "\"}", null);
            assertEquals(200, login.status());
            Session session = new Session(cookie(login, "JSESSIONID"), cookie(login, "XSRF-TOKEN"));
            Response project = request(baseUrl, "POST", "/api/v1/projects",
                    "{\"name\":\"F2-01 files " + System.nanoTime() + "\"}", session);
            assertEquals(201, project.status(), project.body());
            String projectId = json.readTree(project.body()).path("id").asText();

            Response uploaded = multipart(baseUrl, "/api/v1/projects/" + projectId + "/files",
                    session, "payload.json", "REQUEST_FILE", "{\"id\":1}".getBytes(StandardCharsets.UTF_8),
                    "application/json");
            assertEquals(201, uploaded.status(), uploaded.body());
            JsonNode view = json.readTree(uploaded.body());
            assertTrue(view.path("fileId").isTextual());
            assertEquals("ACTIVE", view.path("status").asText());
            assertFalse(view.has("objectKey"));
            assertFalse(view.has("path"));

            Response listed = request(baseUrl, "GET", "/api/v1/projects/" + projectId + "/files?status=ACTIVE",
                    null, session);
            assertEquals(200, listed.status(), listed.body());
            assertEquals(1, json.readTree(listed.body()).size());
            String fileId = view.path("fileId").asText();
            Response archived = request(baseUrl, "POST", "/api/v1/projects/" + projectId + "/files/" + fileId + "/archive",
                    "{\"revision\":0}", session);
            assertEquals(200, archived.status(), archived.body());
            assertEquals("ARCHIVED", json.readTree(archived.body()).path("status").asText());
            assertEquals(0, json.readTree(request(baseUrl, "GET", "/api/v1/projects/" + projectId + "/files?status=ACTIVE",
                    null, session).body()).size());
            assertEquals(1, json.readTree(request(baseUrl, "GET", "/api/v1/projects/" + projectId + "/files?status=ARCHIVED",
                    null, session).body()).size());
            Response invalidStatus = request(baseUrl, "GET", "/api/v1/projects/" + projectId + "/files?status=UPLOADING",
                    null, session);
            assertEquals(400, invalidStatus.status());
            assertTrue(invalidStatus.body().contains("STATUS_INVALID"));
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Accept", "application/json");
        if (session != null) {
            builder.header("Cookie", session.sessionCookie() + "; " + session.csrfCookie());
            builder.header("X-XSRF-TOKEN", session.csrfCookie().substring(session.csrfCookie().indexOf('=') + 1));
        }
        builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), response.headers().allValues("Set-Cookie"));
    }

    private Response multipart(String baseUrl, String path, Session session, String filename, String kind,
                               byte[] content, String mime) throws Exception {
        String boundary = "----f201" + System.nanoTime();
        String prefix = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"kind\"\r\n\r\n" + kind
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\nContent-Type: " + mime + "\r\n\r\n";
        byte[] start = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[start.length + content.length + end.length];
        System.arraycopy(start, 0, payload, 0, start.length);
        System.arraycopy(content, 0, payload, start.length, content.length);
        System.arraycopy(end, 0, payload, start.length + content.length, end.length);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", session.sessionCookie() + "; " + session.csrfCookie())
                .header("X-XSRF-TOKEN", session.csrfCookie().substring(session.csrfCookie().indexOf('=') + 1))
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), response.headers().allValues("Set-Cookie"));
    }

    private static String cookie(Response response, String name) {
        return response.setCookies().stream().map(value -> value.substring(0, value.indexOf(';')))
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
    }

    private record Session(String sessionCookie, String csrfCookie) {
    }

    private record Response(int status, String body, List<String> setCookies) {
    }
}
