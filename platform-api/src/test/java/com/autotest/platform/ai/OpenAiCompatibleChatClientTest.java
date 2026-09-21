package com.autotest.platform.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenAiCompatibleChatClientTest {
    @Test
    void sendsCompatibleRequestAndParsesTextWithoutLeakingSensitivePrompt() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"收到\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient(new ObjectMapper(), ref -> "secret-key");
            AiModelConfigRecord config = new AiModelConfigRecord(UUID.randomUUID(), "测试", 
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture", "model-key",
                    true, 0, Instant.now(), Instant.now(), "OPENAI_COMPATIBLE");
            List<AiStreamEvent> events = client.complete(config,
                    List.of(new AiChatMessage("user", "password=raw-password")), List.of());
            assertEquals("收到", events.get(0).content());
            assertFalse(requestBody.get().contains("raw-password"));
            assertFalse(requestBody.get().contains("secret-key"));
        } finally {
            server.stop(0);
        }
    }
}
