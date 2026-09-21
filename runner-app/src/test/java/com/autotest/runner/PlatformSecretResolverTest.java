package com.autotest.runner;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformSecretResolverTest {

    @Test
    void callsInternalEndpointWithRunnerTokenAndReadsOnlyValue() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        UUID projectId = UUID.randomUUID();
        server.createContext("/api/v1/internal/projects/" + projectId + "/secrets/token/value", exchange -> {
            assertEquals("callback-token", exchange.getRequestHeaders().getFirst("X-Runner-Token"));
            byte[] body = "{\"value\":\"resolver-sentinel\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            String value = new PlatformSecretResolver("http://127.0.0.1:" + server.getAddress().getPort(),
                    "callback-token").resolve(projectId, "token");
            assertEquals("resolver-sentinel", value);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonSuccessResponsesWithoutEchoingResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "secret-response-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new PlatformSecretResolver("http://127.0.0.1:" + server.getAddress().getPort(),
                            "callback-token").resolve(UUID.randomUUID(), "token"));
            assertEquals("Runner 密钥解析失败", failure.getMessage());
        } finally {
            server.stop(0);
        }
    }
}
