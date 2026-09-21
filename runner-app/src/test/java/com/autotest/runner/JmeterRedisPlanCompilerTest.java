package com.autotest.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JmeterRedisPlanCompilerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void compilesOnlyWhitelistedRedisSamplerAndKeepsPasswordAsJmeterProperty() throws Exception {
        Path directory = Files.createTempDirectory("autotest-redis-jmx-");
        Path output = directory.resolve("redis.jmx");
        var plan = json.readTree("""
                {"stepId":"redis-1","host":"redis","port":6379,"databaseNumber":2,
                 "credentialRef":"${secret:redis-pass}","command":"GET","key":"user-${id}",
                 "variableScopes":{"environment":{},"extracted":{"id":"42"}}}
                """);
        new JmeterPlanCompiler().compileRedis(plan, output);
        String xml = Files.readString(output);
        assertTrue(xml.contains("com.autotest.runner.RedisSampler"));
        assertTrue(xml.contains("GET"));
        assertTrue(xml.contains("redis.password"));
        assertFalse(xml.contains("redis-pass"));
        assertFalse(xml.contains("EVAL"));
        assertFalse(xml.contains("JSR223"));
    }

    @Test
    void rejectsUnknownCommandAndUnconfirmedWrites() throws Exception {
        Path output = Files.createTempDirectory("autotest-redis-jmx-").resolve("redis.jmx");
        var plan = json.readTree("{" +
                "\"stepId\":\"redis-1\",\"host\":\"redis\",\"port\":6379,\"command\":\"EVAL\",\"key\":\"k\"}");
        assertThrows(IllegalArgumentException.class, () -> new JmeterPlanCompiler().compileRedis(plan, output));
        var write = ((com.fasterxml.jackson.databind.node.ObjectNode) plan.deepCopy()).put("command", "SET").put("value", "v");
        assertThrows(IllegalArgumentException.class, () -> new JmeterPlanCompiler().compileRedis(write, output));
    }
}
