package com.autotest.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RunCompletionNotifierTest {
    @Test
    void postsOnlyCompletionSummaryWithRunnerToken() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            token.set(exchange.getRequestHeaders().getFirst("X-Runner-Token"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            UUID runId = UUID.randomUUID();
            RunRecord run = new RunRecord(runId, "PASSED", "5.6.3", Instant.now(), Instant.now(), false, 0,
                    "/tmp/secret.jmx", "/tmp/secret.jtl", "/tmp/secret.log",
                    new ObjectMapper().readTree("{\"projectId\":\"p\",\"password\":\"raw-password\"}"));
            new RunCompletionNotifier("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .notify(run);

            assertEquals("/api/v1/internal/runs/" + runId + "/finished", path.get());
            assertEquals("runner-token", token.get());
            assertTrueBodySafe(body.get());
        } finally {
            server.stop(0);
        }
    }

    private static void assertTrueBodySafe(String body) {
        assertFalse(body.contains("raw-password"));
        assertFalse(body.contains("secret.jmx"));
        assertFalse(body.contains("secret.jtl"));
        assertFalse(body.contains("secret.log"));
    }
}
