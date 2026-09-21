package com.autotest.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class RedisCommandExecutorTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void executesGetSetDelAndExistsWithRealRedis() {
        RedisCommandExecutor executor = new RedisCommandExecutor();
        assertFalse(execute(executor, "EXISTS", "missing", null, false, false).sample().success());
        assertTrue(execute(executor, "SET", "user:${id}", "alice", true, true).sample().success());
        RedisCommandExecutor.Result get = execute(executor, "GET", "user:${id}", null, false, false);
        assertTrue(get.sample().success());
        assertEquals("alice", get.extracted().get("value").asText());
        assertTrue(execute(executor, "EXISTS", "user:${id}", null, false, false).sample().success());
        assertTrue(execute(executor, "DEL", "user:${id}", null, true, true).sample().success());
    }

    @Test
    void rejectsWriteWithoutConfirmation() {
        assertThrows(IllegalArgumentException.class,
                () -> execute(new RedisCommandExecutor(), "SET", "danger", "x", false, false));
    }

    @Test
    void resolvesRedisKeyThroughRunVariableContext() throws Exception {
        RunVariableContext context = RunVariableContext.builder("run-1")
                .environment(Map.of("id", json.readTree("\"environment\"")))
                .dataRow(Map.of("id", json.readTree("42")))
                .build();

        assertEquals("user:42", new RedisCommandExecutor().resolveValue("user:${id}", context));
    }

    private RedisCommandExecutor.Result execute(RedisCommandExecutor executor, String command, String key,
                                                String value, boolean allowWrite, boolean confirmed) {
        ObjectNode plan = json.createObjectNode().put("stepId", command).put("host", REDIS.getHost())
                .put("port", REDIS.getMappedPort(6379)).put("databaseNumber", 0).put("command", command)
                .put("key", key).put("allowWrite", allowWrite).put("confirmed", confirmed);
        if (value != null) plan.put("value", value);
        if (command.equals("EXISTS")) plan.putArray("assertions").addObject().put("type", "EXISTS").put("expected", true);
        else plan.putArray("assertions");
        plan.putArray("extractors").addObject().put("variable", "value").put("source", "value").put("failIfMissing", false);
        ObjectNode scopes = plan.putObject("variableScopes");
        scopes.putObject("environment").put("id", "42");
        return executor.execute(plan, null, Map.of("id", json.getNodeFactory().textNode("42")));
    }
}
