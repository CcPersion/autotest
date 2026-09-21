package com.autotest.runner;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunnerConfigurationTest {

    @Test
    void readsRequiredDatabaseAndCallbackSettingsWithSafeDefaults() {
        RunnerConfiguration configuration = RunnerConfiguration.from(Map.of(
                "PLATFORM_DB_URL", "jdbc:postgresql://db:5432/autotest",
                "PLATFORM_DB_USERNAME", "runner",
                "PLATFORM_DB_PASSWORD", "db-password",
                "AUTOTEST_PLATFORM_API_URL", "http://platform-api:8080",
                "AUTOTEST_RUNNER_CALLBACK_TOKEN", "callback-token"));

        assertEquals("jdbc:postgresql://db:5432/autotest", configuration.databaseUrl());
        assertEquals("runner", configuration.databaseUsername());
        assertEquals("db-password", configuration.databasePassword());
        assertEquals("http://platform-api:8080", configuration.platformApiUrl());
        assertEquals("callback-token", configuration.callbackToken());
        org.junit.jupiter.api.Assertions.assertNotNull(configuration.runnerId());
        assertEquals("0.1.0", configuration.runnerVersion());
        assertEquals("5.6.3", configuration.jmeterVersion());
        assertEquals(1000L, configuration.pollIntervalMillis());
        String logText = configuration.toString();
        org.junit.jupiter.api.Assertions.assertFalse(logText.contains("db-password"));
        org.junit.jupiter.api.Assertions.assertFalse(logText.contains("callback-token"));
        org.junit.jupiter.api.Assertions.assertFalse(logText.contains("jdbc:postgresql"));
        org.junit.jupiter.api.Assertions.assertFalse(logText.contains("http://platform-api:8080"));
    }

    @Test
    void rejectsMissingSecretsAndInvalidPollInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> RunnerConfiguration.from(Map.of(
                        "PLATFORM_DB_URL", "jdbc:postgresql://db:5432/autotest",
                        "PLATFORM_DB_USERNAME", "runner",
                        "PLATFORM_DB_PASSWORD", "db-password",
                        "AUTOTEST_PLATFORM_API_URL", "http://platform-api:8080")));

        assertThrows(IllegalArgumentException.class,
                () -> RunnerConfiguration.from(Map.of(
                        "PLATFORM_DB_URL", "jdbc:postgresql://db:5432/autotest",
                        "PLATFORM_DB_USERNAME", "runner",
                        "PLATFORM_DB_PASSWORD", "db-password",
                        "AUTOTEST_PLATFORM_API_URL", "http://platform-api:8080",
                        "AUTOTEST_RUNNER_CALLBACK_TOKEN", "token",
                        "AUTOTEST_RUNNER_POLL_MS", "0")));
    }
}
