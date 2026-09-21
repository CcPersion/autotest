package com.autotest.runner;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** Runner 常驻进程的环境配置；密钥只在内存中使用，不参与日志文本。 */
public record RunnerConfiguration(
        String databaseUrl,
        String databaseUsername,
        String databasePassword,
        String platformApiUrl,
        String callbackToken,
        UUID runnerId,
        String runnerVersion,
        String jmeterVersion,
        Path workRoot,
        String jmeterCommand,
        long pollIntervalMillis) {

    public static RunnerConfiguration from(Map<String, String> environment) {
        if (environment == null) throw new IllegalArgumentException("Runner 环境配置不能为空");
        String databaseUrl = required(environment, "PLATFORM_DB_URL");
        String databaseUsername = required(environment, "PLATFORM_DB_USERNAME");
        String databasePassword = required(environment, "PLATFORM_DB_PASSWORD");
        String platformApiUrl = required(environment, "AUTOTEST_PLATFORM_API_URL");
        String callbackToken = required(environment, "AUTOTEST_RUNNER_CALLBACK_TOKEN");
        UUID runnerId;
        try {
            runnerId = UUID.fromString(optional(environment, "AUTOTEST_RUNNER_ID", UUID.randomUUID().toString()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("AUTOTEST_RUNNER_ID 必须是 UUID", exception);
        }
        String runnerVersion = optional(environment, "AUTOTEST_RUNNER_VERSION", "0.1.0");
        String jmeterVersion = optional(environment, "AUTOTEST_JMETER_VERSION", "5.6.3");
        Path workRoot = Path.of(optional(environment, "AUTOTEST_RUNNER_WORK_ROOT", "/work/runs"));
        String jmeterCommand = optional(environment, "AUTOTEST_JMETER_COMMAND", "jmeter");
        long pollIntervalMillis;
        try {
            pollIntervalMillis = Long.parseLong(optional(environment, "AUTOTEST_RUNNER_POLL_MS", "1000"));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("AUTOTEST_RUNNER_POLL_MS 必须是正整数", exception);
        }
        if (pollIntervalMillis < 100) {
            throw new IllegalArgumentException("AUTOTEST_RUNNER_POLL_MS 不能小于 100 毫秒");
        }
        return new RunnerConfiguration(databaseUrl, databaseUsername, databasePassword, platformApiUrl,
                callbackToken, runnerId, runnerVersion, jmeterVersion, workRoot, jmeterCommand, pollIntervalMillis);
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " 未配置");
        return value;
    }

    private static String optional(Map<String, String> environment, String key, String fallback) {
        String value = environment.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public String toString() {
        return "RunnerConfiguration[runnerId=" + runnerId + ", runnerVersion=" + runnerVersion
                + ", jmeterVersion=" + jmeterVersion
                + ", workRoot=" + workRoot + ", jmeterCommand=" + jmeterCommand
                + ", pollIntervalMillis=" + pollIntervalMillis + "]";
    }
}
