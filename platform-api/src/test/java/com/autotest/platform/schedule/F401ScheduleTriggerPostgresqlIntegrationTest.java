package com.autotest.platform.schedule;

import com.autotest.platform.PlatformApiApplication;
import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.environment.EnvironmentRepository;
import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.secret.SecretService;
import com.autotest.platform.suite.TestSuiteRecord;
import com.autotest.platform.suite.TestSuiteService;
import com.autotest.platform.suite.TestSuiteWrite;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Testcontainers
class F401ScheduleTriggerPostgresqlIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("autotest").withUsername("autotest").withPassword("test-only-password");

    @Test
    void createsDisabledScheduleAndTriggersTheSameSuiteOnceForARepeatedCiKey() {
        try (ConfigurableApplicationContext context = startApplication()) {
            UserAccount actor = context.getBean(UserRepository.class).findByUsername("f4-01-admin@example.com");
            ProjectRecord project = context.getBean(ProjectRepository.class).insert("f4-01-project", "", actor.id());
            var environment = context.getBean(EnvironmentRepository.class).insert(project.id(), "test", "http://127.0.0.1",
                    JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(), actor.id());
            TestSuiteRecord suite = context.getBean(TestSuiteService.class).create(project.id(),
                    new TestSuiteWrite("smoke", "", environment.id(), List.of(), null), actor.id());

            ScheduleRecord schedule = context.getBean(ScheduleService.class).create(project.id(),
                    new ScheduleWrite("nightly", suite.id(), environment.id(), "0 0 * * *", "UTC", false, null), actor.id());
            assertNull(schedule.nextRunAt());

            TriggerTokenService.IssuedToken issued = context.getBean(TriggerTokenService.class)
                    .create(project.id(), "ci", actor.id());
            String storedHash = context.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT token_hash FROM trigger_tokens WHERE id = ?", String.class, issued.token().id());
            assertNotEquals(issued.plaintext(), storedHash);

            TriggerTokenRecord matched = context.getBean(TriggerTokenService.class)
                    .authenticate(project.id(), issued.plaintext());
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            String triggerUrl = "http://127.0.0.1:" + port + "/api/v1/trigger/projects/" + project.id()
                    + "/test-suites/" + suite.id() + "/runs";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Autotest-Trigger-Token", issued.plaintext());
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(Map.of(
                    "environmentId", environment.id().toString(), "idempotencyKey", "ci-build-1"), headers);
            RestTemplate http = new RestTemplate();
            ResponseEntity<com.fasterxml.jackson.databind.JsonNode> first = http.exchange(triggerUrl, HttpMethod.POST,
                    request, com.fasterxml.jackson.databind.JsonNode.class);
            ResponseEntity<com.fasterxml.jackson.databind.JsonNode> second = http.exchange(triggerUrl, HttpMethod.POST,
                    request, com.fasterxml.jackson.databind.JsonNode.class);
            assertEquals(201, first.getStatusCode().value());
            assertEquals(200, second.getStatusCode().value());
            assertEquals(first.getBody().path("id").asText(), second.getBody().path("id").asText());
            assertEquals(1L, context.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM runs WHERE project_id = ? AND idempotency_key = ?", Long.class,
                    project.id(), "ci-build-1"));

            context.getBean(SecretService.class).create(project.id(), "webhook-signing", "not-in-response", actor.id());
            WebhookRecord webhook = context.getBean(WebhookService.class).create(project.id(),
                    new WebhookWrite("ci-hook", "https://example.test/hook", "webhook-signing", List.of("RUN_FINISHED"), false, null),
                    actor.id());
            assertEquals("webhook-signing", webhook.secretRef());
            assertEquals(0L, context.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM webhook_configs WHERE project_id = ? AND url LIKE '%not-in-response%'", Long.class,
                    project.id()));
        }
    }

    private static ConfigurableApplicationContext startApplication() {
        Properties properties = new Properties();
        properties.setProperty("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        properties.setProperty("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        properties.setProperty("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        properties.setProperty("AUTOTEST_ADMIN_USERNAME", "f4-01-admin@example.com");
        properties.setProperty("AUTOTEST_ADMIN_PASSWORD", "F4-01-test-password");
        properties.setProperty("AUTOTEST_MASTER_KEY", "RjEwNC10ZXN0LW1hc3Rlci1rZXktMzItYnl0ZXMhISE=");
        properties.setProperty("autotest.schedule.poll-ms", "600000");
        properties.setProperty("server.port", "0");
        return new SpringApplicationBuilder(PlatformApiApplication.class).properties(properties).run();
    }
}
