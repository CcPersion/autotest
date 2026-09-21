package com.autotest.runner;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerHeartbeatClientTest {

    @Test
    void sendsOnlyRunnerFactsAndKeepsTokenOutOfJsonBody() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runners", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            UUID runnerId = UUID.randomUUID();
            String token = "callback-secret";
            RunnerHeartbeatClient client = new RunnerHeartbeatClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), token, runnerId);
            client.send("0.1.0", "5.6.3", null, 2);
            assertTrue(body.get().contains("\"runnerVersion\":\"0.1.0\""));
            assertTrue(body.get().contains("\"queueDepth\":2"));
            assertFalse(body.get().contains(token));
        } finally {
            server.stop(0);
        }
    }
}
