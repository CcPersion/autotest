package com.autotest.platform.auth;

import com.autotest.platform.PlatformApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class AuthPostgresqlIntegrationTest {

    private static final String ADMIN_USERNAME = "admin@example.com";
    private static final String INITIAL_PASSWORD = "Initial-Password-123!";
    private static final String CHANGED_PASSWORD = "Changed-Password-456!";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("autotest")
            .withUsername("autotest")
            .withPassword("test-only-password");

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void authenticatesOneAdminWithSessionCsrfLockAndPasswordLifecycle() throws Exception {
        try (ConfigurableApplicationContext first = startApplication(INITIAL_PASSWORD)) {
            JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
            String storedUsername = jdbc.queryForObject("SELECT username FROM users LIMIT 1", String.class);
            String storedHash = jdbc.queryForObject("SELECT password_hash FROM users LIMIT 1", String.class);
            assertEquals(ADMIN_USERNAME, storedUsername);
            assertNotEquals(INITIAL_PASSWORD, storedHash);
            assertTrue(storedHash.matches("\\$2[aby]\\$[0-9]{2}\\$.*"));
            String baseUrl = baseUrl(first);

            Response health = request(baseUrl, "GET", "/actuator/health", null, null, null);
            assertEquals(200, health.status());
            assertTrue(health.body().contains("\"status\":\"UP\""));

            Response anonymous = request(baseUrl, "GET", "/api/v1/auth/me", null, null, null);
            assertEquals(401, anonymous.status());
            assertTrue(anonymous.body().contains("traceId"));

            Response anonymousLogout = request(baseUrl, "POST", "/api/v1/auth/logout", null, null, null);
            Response anonymousPassword = request(baseUrl, "PUT", "/api/v1/auth/password",
                    "{\"currentPassword\":\"bad\",\"newPassword\":\"new\"}", null, null);
            assertEquals(401, anonymousLogout.status());
            assertEquals(401, anonymousPassword.status());
            assertTrue(anonymousLogout.body().contains("\"code\""));
            assertTrue(anonymousPassword.body().contains("traceId"));

            Response unknownUser = request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\"nobody@example.com\",\"password\":\"bad\"}", null, null);
            Response wrongPassword = request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\" Admin@Example.com \",\"password\":\"bad\"}", null, null);
            assertEquals(401, unknownUser.status());
            assertEquals(401, wrongPassword.status());
            assertTrue(unknownUser.body().contains("用户名或密码错误"));
            assertTrue(wrongPassword.body().contains("用户名或密码错误"));
            assertEquals(sameError(unknownUser.body()), sameError(wrongPassword.body()));
            assertFalse(unknownUser.body().contains("nobody@example.com"));
            assertFalse(wrongPassword.body().contains(ADMIN_USERNAME));

            String preLoginSession = "JSESSIONID=pre-login-" + UUID.randomUUID();
            Response login = request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\" Admin@Example.com \",\"password\":\"" +
                            INITIAL_PASSWORD + "\"}", preLoginSession, null);
            assertEquals(200, login.status());
            String sessionCookie = sessionCookie(login);
            String csrfCookie = cookie(login, "XSRF-TOKEN");
            assertNotNull(sessionCookie);
            assertNotNull(csrfCookie);
            assertNotEquals(preLoginSession, sessionCookie);
            String rawSessionCookie = rawCookie(login, "JSESSIONID");
            String rawCsrfCookie = rawCookie(login, "XSRF-TOKEN");
            assertTrue(rawSessionCookie.contains("HttpOnly"));
            assertTrue(rawSessionCookie.contains("SameSite=Lax"));
            assertFalse(rawSessionCookie.contains("Secure"));
            assertTrue(rawCsrfCookie.contains("SameSite=Lax"));
            assertFalse(rawCsrfCookie.contains("HttpOnly"));
            assertTrue(login.body().contains("admin@example.com"));

            Response me = request(baseUrl, "GET", "/api/v1/auth/me", null, sessionCookie, csrfCookie);
            assertEquals(200, me.status());
            assertTrue(me.body().contains("admin@example.com"));

            Response csrfRejected = request(baseUrl, "POST", "/api/v1/auth/logout", null, sessionCookie, null);
            assertEquals(403, csrfRejected.status());
            assertTrue(csrfRejected.body().contains("traceId"));

            Response logout = request(baseUrl, "POST", "/api/v1/auth/logout", null, sessionCookie, csrfCookie);
            assertTrue(logout.status() == 200 || logout.status() == 204);
            assertEquals(401, request(baseUrl, "GET", "/api/v1/auth/me", null, sessionCookie, csrfCookie).status());

            for (int attempt = 0; attempt < 5; attempt++) {
                assertEquals(401, request(baseUrl, "POST", "/api/v1/auth/login",
                        "{\"username\":\"admin@example.com\",\"password\":\"bad\"}", null, null).status());
            }
            assertEquals(401, request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\"admin@example.com\",\"password\":\"" + INITIAL_PASSWORD + "\"}", null, null).status());
            Response locked = request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\"admin@example.com\",\"password\":\"" + INITIAL_PASSWORD + "\"}", null, null);
            assertEquals(sameError(unknownUser.body()), sameError(locked.body()));
        }

        try (ConfigurableApplicationContext second = startApplication("different-env-password", true)) {
            String baseUrl = baseUrl(second);
            Response login = request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\"admin@example.com\",\"password\":\"" + INITIAL_PASSWORD + "\"}", null, null);
            assertEquals(200, login.status());
            String sessionCookie = sessionCookie(login);
            String csrfCookie = cookie(login, "XSRF-TOKEN");
            assertTrue(rawCookie(login, "JSESSIONID").contains("Secure"));
            assertTrue(rawCookie(login, "XSRF-TOKEN").contains("Secure"));

            Response passwordChange = request(baseUrl, "PUT", "/api/v1/auth/password",
                    "{\"currentPassword\":\"" + INITIAL_PASSWORD + "\",\"newPassword\":\"" + CHANGED_PASSWORD + "\"}",
                    sessionCookie, csrfCookie);
            assertTrue(passwordChange.status() == 200 || passwordChange.status() == 204);
            assertEquals(1, jdbc(second).queryForObject(
                    "SELECT revision FROM users WHERE username = 'admin@example.com'", Integer.class));
            assertEquals(401, request(baseUrl, "GET", "/api/v1/auth/me", null, sessionCookie, csrfCookie).status());
            assertEquals(401, request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\"admin@example.com\",\"password\":\"" + INITIAL_PASSWORD + "\"}", null, null).status());
            assertEquals(200, request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\"admin@example.com\",\"password\":\"" + CHANGED_PASSWORD + "\"}", null, null).status());
        }
    }

    private static ConfigurableApplicationContext startApplication(String adminPassword) {
        return startApplication(adminPassword, false);
    }

    private static ConfigurableApplicationContext startApplication(String adminPassword, boolean secureCookie) {
        Properties properties = new Properties();
        properties.setProperty("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        properties.setProperty("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        properties.setProperty("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        properties.setProperty("AUTOTEST_ADMIN_USERNAME", " ＡＤＭＩＮ＠ＥＸＡＭＰＬＥ．ＣＯＭ ");
        properties.setProperty("AUTOTEST_ADMIN_PASSWORD", adminPassword);
        properties.setProperty("AUTOTEST_MASTER_KEY", "RjEwNC10ZXN0LW1hc3Rlci1rZXktMzItYnl0ZXMhISE=");
        properties.setProperty("AUTOTEST_SESSION_COOKIE_SECURE", Boolean.toString(secureCookie));
        properties.setProperty("server.port", "0");
        return new SpringApplicationBuilder(PlatformApiApplication.class)
                .properties(properties)
                .run();
    }

    private static JdbcTemplate jdbc(ConfigurableApplicationContext context) {
        return context.getBean(JdbcTemplate.class);
    }

    private static String baseUrl(ConfigurableApplicationContext context) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return "http://127.0.0.1:" + port;
    }

    private Response request(String baseUrl, String method, String path, String body,
                             String sessionCookie, String csrfCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "application/json");
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie + (csrfCookie == null ? "" : "; " + csrfCookie));
        }
        if (csrfCookie != null) {
            builder.header("X-XSRF-TOKEN", csrfCookie.substring(csrfCookie.indexOf('=') + 1));
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
                .filter(value -> !value.startsWith("XSRF-TOKEN="))
                .findFirst()
                .orElse(null);
    }

    private static String cookie(Response response, String name) {
        return response.setCookies().stream()
                .map(value -> value.substring(0, value.indexOf(';')))
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .orElse(null);
    }

    private static String rawCookie(Response response, String name) {
        return response.setCookies().stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .orElse("");
    }

    private static String sameError(String body) {
        return body.replaceAll("\\\"traceId\\\":\\\"[^\\\"]+\\\"", "\\\"traceId\\\":\\\"TRACE\\\"");
    }

    private record Response(int status, String body, List<String> setCookies) {
    }
}
