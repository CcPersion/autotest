package com.autotest.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretFileMaterializerTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void writesSecretOnlyToRestrictedRunFileAndCleansItAfterClose() throws Exception {
        Path root = Files.createTempDirectory("secret-materializer-");
        String value = "secret-materializer-sentinel";
        SecretFileMaterializer materializer = new SecretFileMaterializer(root, UUID.randomUUID(),
                (projectId, name) -> {
                    assertEquals("token", name);
                    return value;
                });

        var result = materializer.materialize(json.readTree("""
                {"headers":[{"name":"Authorization","value":"Bearer ${secret:token}"}],
                 "body":{"type":"JSON","value":{"token":"${secret:token}"}}}
                """));

        String text = result.toString();
        assertFalse(text.contains(value));
        assertTrue(text.contains("__autotestSecret("));
        Path directory = root.resolve("secret-files");
        Path secretFile;
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
        try (var files = Files.list(directory)) {
            secretFile = files.findFirst().orElseThrow();
        }
        assertEquals(value, Files.readString(secretFile));

        materializer.close();
        assertFalse(Files.exists(directory));
    }

    @Test
    void resolvesEachSecretOnceAndRejectsEmptySecret() throws Exception {
        Path root = Files.createTempDirectory("secret-materializer-dedup-");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        try (SecretFileMaterializer materializer = new SecretFileMaterializer(root, UUID.randomUUID(),
                (projectId, name) -> {
                    calls.incrementAndGet();
                    return "value";
                })) {
            var result = materializer.materialize(json.readTree(
                    "{\"one\":\"${secret:token}\",\"two\":\"${secret:token}\"}"));
            assertEquals(result.get("one").asText(), result.get("two").asText());
            assertEquals(1, calls.get());
        }

        SecretFileMaterializer empty = new SecretFileMaterializer(root, UUID.randomUUID(),
                (projectId, name) -> "");
        assertThrows(IllegalArgumentException.class,
                () -> empty.materialize(json.readTree("{\"value\":\"${secret:empty}\"}")));
        empty.close();
    }
}
