package com.autotest.runner;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyCapabilityClientTest {

    @Test
    void requestsRunBoundCapabilityWithoutTrustingClientAddressAsPolicy() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> runnerToken = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            runnerToken.set(exchange.getRequestHeaders().getFirst("X-Runner-Token"));
            byte[] body = "{\"capability\":\"signed-capability\",\"expiresAt\":\"2030-01-01T00:00:00Z\",\"addresses\":[\"192.0.2.10\"],\"proxyAddresses\":[\"192.0.2.11\"]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            JmeterPlan plan = new JmeterPlan("plan", "step", "http://allowed.test:8080", "GET", "/health",
                    List.of(), List.of(), JmeterBody.none(), Map.of(), List.of(), List.of(), true, 0, 0,
                    new JmeterProxy("http", "proxy.test", 8081, "", ""), List.of(), null,
                    List.of("allowed.test:8080", "proxy.test:8081"), true, true);
            ProxyCapabilityClient client = new ProxyCapabilityClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "runner-secret",
                    new ApprovedDnsResolver(host -> new InetAddress[]{
                            InetAddress.getByName(host.equals("allowed.test") ? "192.0.2.10" : "192.0.2.11")}),
                    HttpClient.newHttpClient());

            JmeterPlan authorized = client.authorize(java.util.UUID.randomUUID(), plan);

            assertEquals("signed-capability", authorized.proxy().capability());
            assertEquals("runner-secret", runnerToken.get());
            assertTrue(requestBody.get().contains("allowed.test:8080"));
            assertTrue(requestBody.get().contains("proxy.test:8081"));
            assertTrue(!requestBody.get().contains("runner-secret"));
        } finally {
            server.stop(0);
        }
    }
}
