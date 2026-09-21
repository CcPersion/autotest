package com.autotest.platform.ai;

import com.autotest.platform.PlatformApiApplication;
import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class AiSecurityPostgresqlIntegrationTest {
    private static final String ADMIN_PASSWORD = "F3-05-password-123!";
    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(
            "F305-test-master-key-32-bytes!!!".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("autotest")
            .withUsername("autotest")
            .withPassword("test-only-password");

    @Test
    void persistsOnlySanitizedAiMessagesAndToolResults() {
        try (ConfigurableApplicationContext context = startApplication()) {
            UserAccount actor = context.getBean(UserRepository.class).findByUsername("owner@example.com");
            var project = context.getBean(ProjectRepository.class).insert("F3-05 project", "", actor.id());
            var model = context.getBean(AiModelConfigService.class).create(
                    new AiModelConfigWrite("Fake 安全模型", "fake://model", "fixture", null, true, null), actor.id());
            AiSessionService sessions = context.getBean(AiSessionService.class);
            var session = sessions.create(project.id(), new AiSessionWrite(model.id(), "安全回归"), actor.id());

            sessions.send(project.id(), session.id(), "请列出当前项目，password=raw-password token=raw-token");

            var messages = context.getBean(AiSessionRepository.class).findMessages(session.id());
            String persisted = messages.toString();
            assertFalse(persisted.contains("raw-password"));
            assertFalse(persisted.contains("raw-token"));
            assertTrue(messages.stream().anyMatch(message -> "TOOL_RESULT".equals(message.eventType())));
        }
    }

    private ConfigurableApplicationContext startApplication() {
        var properties = new java.util.Properties();
        properties.setProperty("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        properties.setProperty("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        properties.setProperty("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        properties.setProperty("AUTOTEST_ADMIN_USERNAME", "owner@example.com");
        properties.setProperty("AUTOTEST_ADMIN_PASSWORD", ADMIN_PASSWORD);
        properties.setProperty("AUTOTEST_MASTER_KEY", MASTER_KEY);
        properties.setProperty("server.port", "0");
        return new SpringApplicationBuilder(PlatformApiApplication.class).properties(properties).run();
    }
}
