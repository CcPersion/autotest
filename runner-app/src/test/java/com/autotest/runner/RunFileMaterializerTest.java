package com.autotest.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunFileMaterializerTest {
    @Test
    void downloadsOnlyRunBoundFileAndVerifiesSnapshot(@TempDir Path temp) throws Exception {
        byte[] content = "F2-01 file".getBytes(StandardCharsets.UTF_8);
        UUID runId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/v1/internal/runs/" + runId + "/files/" + fileId,
                    exchange -> {
                        assertEquals("runner-token", exchange.getRequestHeaders().getFirst("X-Runner-Token"));
                        exchange.sendResponseHeaders(200, content.length);
                        exchange.getResponseBody().write(content);
                        exchange.close();
                    });
            server.start();
            String planJson = "{\"projectId\":\"" + UUID.randomUUID() + "\",\"body\":{\"type\":\"MULTIPART\","
                    + "\"value\":{\"files\":[{\"fileId\":\"" + fileId + "\",\"size\":" + content.length
                    + ",\"sha256\":\"" + sha + "\",\"mimeType\":\"text/plain\"}]}}}";
            var plan = new ObjectMapper().readTree(planJson);
            JsonNodeHolder holder = new JsonNodeHolder(new RunFileMaterializer(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .materialize(plan, runId, temp));
            Path file = Path.of(holder.node().path("body").path("value").path("files").get(0).path("path").asText());
            assertTrue(Files.isRegularFile(file));
            assertEquals("F2-01 file", Files.readString(file));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void multipartCompactArrayDoesNotDownloadTextParts(@TempDir Path temp) throws Exception {
        byte[] content = "F2-01 file".getBytes(StandardCharsets.UTF_8);
        UUID runId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/v1/internal/runs/" + runId + "/files/" + fileId,
                    exchange -> {
                        exchange.sendResponseHeaders(200, content.length);
                        exchange.getResponseBody().write(content);
                        exchange.close();
                    });
            server.start();
            String planJson = "{\"projectId\":\"" + UUID.randomUUID() + "\",\"body\":{\"type\":\"MULTIPART\","
                    + "\"value\":[{\"name\":\"note\",\"kind\":\"TEXT\",\"value\":\"text\"},"
                    + "{\"name\":\"upload\",\"kind\":\"FILE\",\"fileId\":\"" + fileId
                    + "\",\"size\":" + content.length + ",\"sha256\":\"" + sha
                    + "\",\"mimeType\":\"text/plain\"}]}}";
            var plan = new ObjectMapper().readTree(planJson);

            var materialized = new RunFileMaterializer(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .materialize(plan, runId, temp);

            assertFalse(materialized.path("body").path("value").get(0).has("path"));
            assertTrue(Files.isRegularFile(Path.of(materialized.path("body").path("value").get(1)
                    .path("path").asText())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void materializesClientCertificateSnapshotNestedInOptions(@TempDir Path temp) throws Exception {
        byte[] content = "F2-01 p12".getBytes(StandardCharsets.UTF_8);
        UUID runId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/v1/internal/runs/" + runId + "/files/" + fileId,
                    exchange -> {
                        exchange.sendResponseHeaders(200, content.length);
                        exchange.getResponseBody().write(content);
                        exchange.close();
                    });
            server.start();
            String planJson = "{\"options\":{\"clientCertificate\":{\"type\":\"PKCS12\","
                    + "\"fileSnapshot\":{\"fileId\":\"" + fileId + "\",\"size\":" + content.length
                    + ",\"sha256\":\"" + sha + "\",\"mimeType\":\"application/x-pkcs12\"}}}}";
            var plan = new ObjectMapper().readTree(planJson);

            var materialized = new RunFileMaterializer(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .materialize(plan, runId, temp);

            var certificate = materialized.path("options").path("clientCertificate");
            Path file = Path.of(certificate.path("fileSnapshot").path("path").asText());
            assertTrue(Files.isRegularFile(file));
            assertEquals(file.toString(), certificate.path("filePath").asText());
            assertEquals("F2-01 p12", Files.readString(file));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exposesFixedPlatformDownloadErrorCodeWithoutLeakingResponseBody(@TempDir Path temp) throws Exception {
        UUID runId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/v1/internal/runs/" + runId + "/files/" + fileId,
                    exchange -> {
                        byte[] response = "{\"code\":\"SNAPSHOT_METADATA_MISMATCH\",\"message\":\"private\"}"
                                .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(409, response.length);
                        exchange.getResponseBody().write(response);
                        exchange.close();
                    });
            server.start();
            String planJson = "{\"body\":{\"type\":\"MULTIPART\",\"value\":[{\"kind\":\"FILE\","
                    + "\"fileId\":\"" + fileId + "\",\"size\":1,\"sha256\":\"sha\",\"mimeType\":\"text/plain\"}]}}";
            var plan = new ObjectMapper().readTree(planJson);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                    new RunFileMaterializer("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                            .materialize(plan, runId, temp));

            assertEquals("SNAPSHOT_METADATA_MISMATCH", error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private record JsonNodeHolder(com.fasterxml.jackson.databind.JsonNode node) {
    }
}
