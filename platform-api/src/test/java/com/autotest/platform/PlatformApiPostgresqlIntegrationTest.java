package com.autotest.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class PlatformApiPostgresqlIntegrationTest {

    private static final Set<String> CORE_TABLES = Set.of(
            "users",
            "projects",
            "modules",
            "environments",
            "api_definitions",
            "api_cases",
            "runs",
            "step_results",
            "secrets",
            "scenarios",
            "scenario_steps",
            "jdbc_data_sources",
            "redis_data_sources",
            "test_suites",
            "test_suite_members",
            "ai_model_configs",
            "ai_sessions",
            "ai_messages",
            "schedules",
            "trigger_tokens",
            "webhook_configs",
            "project_retention_settings",
            "audit_events",
            "runner_status",
            "file_assets",
            "file_asset_quota_reservations",
            "file_asset_orphan_objects"
    );

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("autotest")
            .withUsername("autotest")
            .withPassword("test-only-password");

    @Test
    void startsTwiceWithTheSamePostgresqlDatabaseAndExposesSafeHealth() {
        Map<String, Long> firstCounts;

        try (ConfigurableApplicationContext first = startApplication()) {
            JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
            assertCoreSchema(jdbc);
            firstCounts = businessRowCounts(jdbc);
            assertHealth(first);
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            JdbcTemplate jdbc = second.getBean(JdbcTemplate.class);
            assertCoreSchema(jdbc);
            assertEquals(firstCounts, businessRowCounts(jdbc));
            assertHealth(second);
        }
    }

    private static ConfigurableApplicationContext startApplication() {
        Properties properties = new Properties();
        properties.setProperty("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        properties.setProperty("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        properties.setProperty("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        properties.setProperty("AUTOTEST_ADMIN_USERNAME", "f1-01-admin@example.com");
        properties.setProperty("AUTOTEST_ADMIN_PASSWORD", "F1-01-test-password");
        properties.setProperty("AUTOTEST_MASTER_KEY", "RjEwNC10ZXN0LW1hc3Rlci1rZXktMzItYnl0ZXMhISE=");
        properties.setProperty("server.port", "0");
        return new SpringApplicationBuilder(PlatformApiApplication.class)
                .properties(properties)
                .run();
    }

    private static void assertCoreSchema(JdbcTemplate jdbc) {
        Integer migrationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE",
                Integer.class
        );
        assertEquals(17, migrationCount);

        Set<String> actualTables = new HashSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'",
                String.class
        ));
        assertEquals(CORE_TABLES, actualTables);
        assertTrue(jdbc.queryForObject(
                "SELECT autotest_has_plain_sensitive_value(?::jsonb)", Boolean.class,
                "{\"body\":{\"password\":\"plain-value\"}}"));
        assertFalse(jdbc.queryForObject(
                "SELECT autotest_has_plain_sensitive_value(?::jsonb)", Boolean.class,
                "{\"body\":{\"password\":\"${secret:fixture-password}\"}}"));
    }

    private static Map<String, Long> businessRowCounts(JdbcTemplate jdbc) {
        Map<String, Long> counts = new HashMap<>();
        for (String table : CORE_TABLES) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        return counts;
    }

    private static void assertHealth(ConfigurableApplicationContext context) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        ResponseEntity<String> response = new RestTemplate().getForEntity(
                "http://127.0.0.1:" + port + "/actuator/health",
                String.class
        );

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertTrue(body != null && body.contains("\"status\":\"UP\""));
        String lowerBody = body == null ? "" : body.toLowerCase();
        assertFalse(lowerBody.contains("jdbc"));
        assertFalse(lowerBody.contains("autotest"));
        assertFalse(lowerBody.contains(POSTGRES.getPassword()));
    }
}
