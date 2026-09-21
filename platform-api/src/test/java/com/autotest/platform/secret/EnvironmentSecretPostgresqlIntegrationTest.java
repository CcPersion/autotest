package com.autotest.platform.secret;

import com.autotest.platform.PlatformApiApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class EnvironmentSecretPostgresqlIntegrationTest {

    private static final String ADMIN_PASSWORD = "F1-04-password-123!";
    private static final String SECRET_VALUE = "F104_SECRET_SENTINEL_8d3a9e4c_not_for_logs";
    private static final String REPLACED_SECRET_VALUE = "F104_REPLACED_SENTINEL_b7e1d2c9";
    private static final String CALLBACK_TOKEN = "f2-02-runner-callback-token";
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
    void managesTypedEnvironmentsAndOpaqueProjectSecrets(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = startApplication(MASTER_KEY)) {
            String baseUrl = baseUrl(context);
            Response login = request(baseUrl, "POST", "/api/v1/auth/login",
                    "{\"username\":\"owner@example.com\",\"password\":\"" + ADMIN_PASSWORD + "\"}", null, null);
            assertEquals(200, login.status());
            String session = cookie(login, "JSESSIONID");
            String csrf = cookie(login, "XSRF-TOKEN");

            Response projectOne = request(baseUrl, "POST", "/api/v1/projects",
                    "{\"name\":\"F1-04 project\"}", session, csrf);
            assertEquals(201, projectOne.status());
            String projectOneId = field(projectOne.body(), "id");
            Response projectTwo = request(baseUrl, "POST", "/api/v1/projects",
                    "{\"name\":\"F1-04 other\"}", session, csrf);
            assertEquals(201, projectTwo.status());
            String projectTwoId = field(projectTwo.body(), "id");

            for (String invalidName : new String[]{" token ", "中文", "token name", "token$", "{token}"}) {
                Response invalidSecret = request(baseUrl, "POST",
                        "/api/v1/projects/" + projectOneId + "/secrets",
                        "{\"name\":\"" + invalidName + "\",\"value\":\"not-created\"}", session, csrf);
                assertEquals(400, invalidSecret.status());
                assertTrue(invalidSecret.body().contains("VALIDATION_FAILED"));
            }

            Response secret = request(baseUrl, "POST", "/api/v1/projects/" + projectOneId + "/secrets",
                    "{\"name\":\"token\",\"value\":\"" + SECRET_VALUE + "\"}", session, csrf);
            assertEquals(201, secret.status());
            String secretId = field(secret.body(), "id");
            assertEquals("••••••••", field(secret.body(), "mask"));
            assertOpaque(secret.body());

            Response runnerSecret = runnerRequest(baseUrl,
                    "/api/v1/internal/projects/" + projectOneId + "/secrets/token/value",
                    CALLBACK_TOKEN);
            assertEquals(200, runnerSecret.status());
            assertEquals(SECRET_VALUE, field(runnerSecret.body(), "value"));
            assertEquals(401, runnerRequest(baseUrl,
                    "/api/v1/internal/projects/" + projectOneId + "/secrets/token/value",
                    "wrong-runner-token").status());

            Response environment = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/environments",
                    "{\"name\":\" QA \",\"baseUrl\":\"https://example.test/api\","
                            + "\"variables\":{\"text\":\"hello\",\"number\":7,\"flag\":true,"
                            + "\"nothing\":null,\"nested\":{\"ref\":\"prefix ${secret:token}\","
                            + "\"array\":[1,\"${secret:token}\"]}},"
                            + "\"requestOptions\":{\"defaultHeaders\":[{\"name\":\"X-Trace\",\"value\":\"qa\",\"enabled\":true}],"
                            + "\"followRedirects\":false,\"responseTimeoutMillis\":5000,"
                            + "\"proxy\":{\"scheme\":\"http\",\"host\":\"proxy.example\",\"port\":8080,"
                            + "\"password\":\"${secret:token}\"}}}", session, csrf);
            assertEquals(201, environment.status());
            String environmentId = field(environment.body(), "id");
            assertEquals(0, intField(environment.body(), "revision"));
            JsonNode variables = json.readTree(environment.body()).get("variables");
            assertTrue(variables.get("number").isInt());
            assertTrue(variables.get("flag").isBoolean());
            assertTrue(variables.get("nothing").isNull());
            assertEquals("prefix ${secret:token}", variables.get("nested").get("ref").asText());
            JsonNode requestOptions = json.readTree(environment.body()).get("requestOptions");
            assertEquals("qa", requestOptions.get("defaultHeaders").get(0).get("value").asText());
            assertFalse(requestOptions.get("followRedirects").asBoolean());
            assertEquals(5000, requestOptions.get("responseTimeoutMillis").asInt());
            assertEquals("${secret:token}", requestOptions.get("proxy").get("password").asText());
            assertFalse(environment.body().contains(SECRET_VALUE));

            Response invalidRequestOptions = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/environments",
                    "{\"name\":\"bad-defaults\",\"baseUrl\":\"https://example.test\",\"variables\":{},"
                            + "\"requestOptions\":{\"defaultHeaders\":[{\"name\":\"Authorization\","
                            + "\"value\":\"Bearer plaintext\",\"enabled\":true}]}}", session, csrf);
            assertEquals(400, invalidRequestOptions.status());

            Response duplicateEnvironment = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/environments",
                    "{\"name\":\"qa\",\"baseUrl\":\"https://other.test\",\"variables\":{}}", session, csrf);
            assertEquals(409, duplicateEnvironment.status());

            Response staleEnvironment = request(baseUrl, "PUT",
                    "/api/v1/projects/" + projectOneId + "/environments/" + environmentId,
                    "{\"name\":\"QA2\",\"baseUrl\":\"https://example.test\",\"variables\":{},\"revision\":9}",
                    session, csrf);
            assertEquals(409, staleEnvironment.status());
            assertTrue(staleEnvironment.body().contains("REVISION_CONFLICT"));

            Response invalidUrl = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/environments",
                    "{\"name\":\"bad-url\",\"baseUrl\":\"https://user:pass@example.test/#fragment\",\"variables\":{}}",
                    session, csrf);
            assertEquals(400, invalidUrl.status());

            Response invalidVariables = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/environments",
                    "{\"name\":\"bad-vars\",\"baseUrl\":\"https://example.test\",\"variables\":[]}",
                    session, csrf);
            assertEquals(400, invalidVariables.status());

            Response missingReference = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/environments",
                    "{\"name\":\"missing-ref\",\"baseUrl\":\"https://example.test\","
                            + "\"variables\":{\"nested\":[{\"value\":\"${secret:no-such-secret}\"}]}}",
                    session, csrf);
            assertEquals(400, missingReference.status());
            assertTrue(missingReference.body().contains("SECRET_REFERENCE_NOT_FOUND"));

            for (String invalidReference : new String[]{"${secret: token }", "${secret: }", "${secret:}",
                    "${secret:token$}", "${secret:token"}) {
                Response malformedReference = request(baseUrl, "POST",
                        "/api/v1/projects/" + projectOneId + "/environments",
                        "{\"name\":\"malformed-" + Math.abs(invalidReference.hashCode())
                                + "\",\"baseUrl\":\"https://example.test\","
                                + "\"variables\":{\"ref\":\"" + invalidReference + "\"}}",
                        session, csrf);
                assertEquals(400, malformedReference.status());
                assertTrue(malformedReference.body().contains("VALIDATION_FAILED"));
            }

            Response foreignEnvironment = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectTwoId + "/environments",
                    "{\"name\":\"foreign\",\"baseUrl\":\"https://foreign.test\",\"variables\":{}}",
                    session, csrf);
            assertEquals(201, foreignEnvironment.status());
            String foreignEnvironmentId = field(foreignEnvironment.body(), "id");
            assertEquals(404, request(baseUrl, "GET",
                    "/api/v1/projects/" + projectOneId + "/environments/" + foreignEnvironmentId,
                    null, session, csrf).status());

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            String beforeNonce = jdbc.queryForObject("SELECT encode(nonce, 'hex') FROM secrets WHERE id = ?",
                    String.class, UUID.fromString(secretId));
            String dbSecretRepresentation = jdbc.queryForObject(
                    "SELECT name || ':' || encode(ciphertext, 'hex') || ':' || encode(nonce, 'hex') FROM secrets WHERE id = ?",
                    String.class, UUID.fromString(secretId));
            assertFalse(dbSecretRepresentation.contains(SECRET_VALUE));
            assertFalse(dbSecretRepresentation.contains(REPLACED_SECRET_VALUE));

            Response replacement = request(baseUrl, "PUT",
                    "/api/v1/projects/" + projectOneId + "/secrets/" + secretId,
                    "{\"value\":\"" + REPLACED_SECRET_VALUE + "\",\"revision\":0}", session, csrf);
            assertEquals(200, replacement.status());
            assertEquals(1, intField(replacement.body(), "revision"));
            assertOpaque(replacement.body());
            String afterNonce = jdbc.queryForObject("SELECT encode(nonce, 'hex') FROM secrets WHERE id = ?",
                    String.class, UUID.fromString(secretId));
            assertNotEquals(beforeNonce, afterNonce);

            Response secretList = request(baseUrl, "GET",
                    "/api/v1/projects/" + projectOneId + "/secrets", null, session, csrf);
            assertEquals(200, secretList.status());
            assertTrue(secretList.body().contains(secretId));
            assertOpaque(secretList.body());

            Response archivedSecret = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/secrets/" + secretId + "/archive",
                    "{\"revision\":1}", session, csrf);
            assertEquals(200, archivedSecret.status());
            assertTrue(json.readTree(archivedSecret.body()).get("archived").asBoolean());
            assertOpaque(archivedSecret.body());
            Response activeSecrets = request(baseUrl, "GET",
                    "/api/v1/projects/" + projectOneId + "/secrets", null, session, csrf);
            assertEquals(200, activeSecrets.status());
            assertFalse(activeSecrets.body().contains(secretId));
            Response allSecrets = request(baseUrl, "GET",
                    "/api/v1/projects/" + projectOneId + "/secrets?includeArchived=true",
                    null, session, csrf);
            assertEquals(200, allSecrets.status());
            assertTrue(allSecrets.body().contains(secretId));
            assertOpaque(allSecrets.body());

            Response archivedEnvironment = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/environments/" + environmentId + "/archive",
                    "{\"revision\":0}", session, csrf);
            assertEquals(200, archivedEnvironment.status());
            assertTrue(json.readTree(archivedEnvironment.body()).get("archived").asBoolean());
            Response activeEnvironments = request(baseUrl, "GET",
                    "/api/v1/projects/" + projectOneId + "/environments", null, session, csrf);
            assertEquals(200, activeEnvironments.status());
            assertFalse(activeEnvironments.body().contains(environmentId));
            Response allEnvironments = request(baseUrl, "GET",
                    "/api/v1/projects/" + projectOneId + "/environments?includeArchived=true",
                    null, session, csrf);
            assertEquals(200, allEnvironments.status());
            assertTrue(allEnvironments.body().contains(environmentId));
            Response restoredEnvironment = request(baseUrl, "POST",
                    "/api/v1/projects/" + projectOneId + "/environments/" + environmentId + "/restore",
                    "{\"revision\":1}", session, csrf);
            assertEquals(200, restoredEnvironment.status());
            assertFalse(json.readTree(restoredEnvironment.body()).get("archived").asBoolean());

            assertCryptoAad(context, projectOneId, secretId, jdbc);

            Response archivedProject = request(baseUrl, "POST", "/api/v1/projects/" + projectOneId + "/archive",
                    "{\"revision\":0}", session, csrf);
            assertEquals(200, archivedProject.status());
            assertEquals(409, request(baseUrl, "POST", "/api/v1/projects/" + projectOneId + "/secrets",
                    "{\"name\":\"blocked\",\"value\":\"blocked\"}", session, csrf).status());
            assertEquals(409, request(baseUrl, "PUT",
                    "/api/v1/projects/" + projectOneId + "/environments/" + environmentId,
                    "{\"name\":\"blocked\",\"baseUrl\":\"https://example.test\",\"variables\":{},\"revision\":0}",
                    session, csrf).status());
            assertFalse(output.getAll().contains(SECRET_VALUE));
            assertFalse(output.getAll().contains(REPLACED_SECRET_VALUE));
        }
    }

    @Test
    void rejectsMissingMalformedAndWrongLengthMasterKeysWithoutEchoingInput(CapturedOutput output) {
        byte[] thirtyOne = new byte[31];
        byte[] thirtyThree = new byte[33];
        String[] invalidKeys = {"", "F104_INVALID_BASE64_SENTINEL", Base64.getEncoder().encodeToString(thirtyOne),
                Base64.getEncoder().encodeToString(thirtyThree)};
        for (String invalidKey : invalidKeys) {
            ConfigurableApplicationContext context = null;
            try {
                context = startApplication(invalidKey);
                throw new AssertionError("invalid master key unexpectedly started");
            } catch (Exception exception) {
                String messages = messages(exception);
                if (!invalidKey.isEmpty()) {
                    assertFalse(messages.contains(invalidKey));
                }
                assertTrue(messages.contains("AUTOTEST_MASTER_KEY"));
            } finally {
                if (context != null) {
                    context.close();
                }
            }
        }
        for (String invalidKey : invalidKeys) {
            if (!invalidKey.isEmpty()) {
                assertFalse(output.getAll().contains(invalidKey));
            }
        }
    }

    private void assertCryptoAad(ConfigurableApplicationContext context, String projectId, String secretId,
                                 JdbcTemplate jdbc) throws Exception {
        byte[] ciphertext = jdbc.queryForObject("SELECT ciphertext FROM secrets WHERE id = ?", byte[].class,
                UUID.fromString(secretId));
        byte[] nonce = jdbc.queryForObject("SELECT nonce FROM secrets WHERE id = ?", byte[].class,
                UUID.fromString(secretId));
        Object crypto = context.getBean("secretCryptoService");
        Method decrypt = crypto.getClass().getMethod("decrypt", UUID.class, UUID.class, byte[].class, byte[].class);
        byte[] plaintext = (byte[]) decrypt.invoke(crypto, UUID.fromString(projectId), UUID.fromString(secretId),
                ciphertext, nonce);
        assertArrayEquals(REPLACED_SECRET_VALUE.getBytes(StandardCharsets.UTF_8), plaintext);
        InvocationTargetException wrongProject = assertThrows(InvocationTargetException.class,
                () -> decrypt.invoke(crypto, UUID.randomUUID(), UUID.fromString(secretId), ciphertext, nonce));
        assertTrue(wrongProject.getCause().getMessage().contains("SECRET_CRYPTO_ERROR"));
        InvocationTargetException wrongSecret = assertThrows(InvocationTargetException.class,
                () -> decrypt.invoke(crypto, UUID.fromString(projectId), UUID.randomUUID(), ciphertext, nonce));
        assertTrue(wrongSecret.getCause().getMessage().contains("SECRET_CRYPTO_ERROR"));
    }

    private static ConfigurableApplicationContext startApplication(String masterKey) {
        Properties properties = new Properties();
        properties.setProperty("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        properties.setProperty("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        properties.setProperty("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        properties.setProperty("AUTOTEST_ADMIN_USERNAME", "owner@example.com");
        properties.setProperty("AUTOTEST_ADMIN_PASSWORD", ADMIN_PASSWORD);
        properties.setProperty("AUTOTEST_MASTER_KEY", masterKey);
        properties.setProperty("autotest.runner.callback-token", CALLBACK_TOKEN);
        properties.setProperty("server.port", "0");
        return new SpringApplicationBuilder(PlatformApiApplication.class).properties(properties).run();
    }

    private static String messages(Throwable throwable) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
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

    private Response runnerRequest(String baseUrl, String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .header("X-Runner-Token", token)
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), response.headers().allValues("Set-Cookie"));
    }

    private static String cookie(Response response, String name) {
        return response.setCookies().stream()
                .map(value -> value.substring(0, value.indexOf(';')))
                .filter(value -> value.startsWith(name + "="))
                .findFirst().orElseThrow();
    }

    private String field(String body, String field) throws Exception {
        return json.readTree(body).get(field).asText();
    }

    private int intField(String body, String field) throws Exception {
        return json.readTree(body).get(field).asInt();
    }

    private void assertOpaque(String body) {
        assertFalse(body.contains("value"));
        assertFalse(body.contains("ciphertext"));
        assertFalse(body.contains("nonce"));
        assertFalse(body.contains(SECRET_VALUE));
        assertFalse(body.contains(REPLACED_SECRET_VALUE));
    }

    private record Response(int status, String body, List<String> setCookies) {
    }
}
