package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JtlResultUploaderTest {

    @Test
    void postsSanitizedStepSummariesWithRunnerTokenAndStableResultKeys() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs", exchange -> {
            assertEquals("runner-token", exchange.getRequestHeaders().getFirst("X-Runner-Token"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            UUID runId = UUID.randomUUID();
            new JtlResultUploader("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .upload(runId, List.of(new JtlSample("step-1", 12, 200, "OK", true, "",
                            "http://test.local/p?token=***")));

            assertEquals(1, bodies.size());
            JsonNode body = json.readTree(bodies.get(0));
            assertEquals("step-1#0", body.get("resultKey").asText());
            assertEquals("PASSED", body.get("status").asText());
            assertTrue(body.get("requestSummary").get("url").asText().contains("token=***"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesMultipleJmeterAssertionFailuresAsSeparateReportItems() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            new JtlResultUploader("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .upload(UUID.randomUUID(), List.of(new JtlSample("step-1", 12, 500, "ERR", false,
                            "状态码断言失败\n正文断言失败", "http://test.local/p")));

            JsonNode assertions = json.readTree(bodies.get(0)).get("assertions");
            assertEquals(2, assertions.size());
            assertEquals("状态码断言失败", assertions.get(0).get("message").asText());
            assertEquals("正文断言失败", assertions.get(1).get("message").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void uploadsTypedExtractionValuesAndMasksSensitiveVariableNames() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            String extractionJson = "[{\"variable\":\"profile\",\"type\":\"JSON_PATH\",\"matched\":true,"
                    + "\"value\":{\"roles\":[\"qa\"]}},{\"variable\":\"accessToken\",\"type\":\"JSON_PATH\","
                    + "\"matched\":true,\"value\":\"secret-token\"}]";
            new JtlResultUploader("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .upload(UUID.randomUUID(), List.of(new JtlSample("step-1", 12, 200, "OK", true, "",
                            "http://test.local/p", extractionJson)));

            JsonNode extractions = json.readTree(bodies.get(0)).get("extractions");
            assertTrue(extractions.isArray());
            assertEquals("qa", extractions.get(0).get("value").get("roles").get(0).asText());
            assertEquals("***", extractions.get(1).get("value").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesFrozenExtractionFactsAndMasksHeaderCookieByExpressionAndKnownValue() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            String sentinel = "f2-03-header-cookie-sentinel";
            String extractionJson = "[{" +
                    "\"ruleIndex\":2,\"type\":\"HEADER\",\"expression\":\"Authorization\"," +
                    "\"variable\":\"headerValue\",\"matched\":true,\"usedDefault\":false," +
                    "\"value\":\"" + sentinel + "\",\"valueType\":\"string\",\"failed\":false," +
                    "\"errorCode\":\"\",\"message\":\"已命中\"},{" +
                    "\"ruleIndex\":3,\"type\":\"COOKIE\",\"expression\":\"session\"," +
                    "\"variable\":\"cookieValue\",\"matched\":false,\"usedDefault\":true," +
                    "\"value\":\"fallback\",\"valueType\":\"string\",\"failed\":false," +
                    "\"errorCode\":\"\",\"message\":\"未命中，使用默认值\"}]";
            new JtlResultUploader("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .upload(UUID.randomUUID(), List.of(new JtlSample("step-1", 12, 200, "OK", true, "",
                            "http://test.local/p", extractionJson, "{}", "GET", "", 
                            "Authorization: " + sentinel + "\nSet-Cookie: session=" + sentinel + "; Path=/", List.of())));

            JsonNode extractions = json.readTree(bodies.get(0)).get("extractions");
            assertEquals(2, extractions.size());
            assertEquals(2, extractions.get(0).get("ruleIndex").asInt());
            assertEquals("Authorization", extractions.get(0).get("expression").asText());
            assertEquals("string", extractions.get(0).get("valueType").asText());
            assertEquals("", extractions.get(0).get("errorCode").asText());
            assertEquals("***", extractions.get(0).get("value").asText());
            assertEquals(3, extractions.get(1).get("ruleIndex").asInt());
            assertTrue(extractions.get(1).get("usedDefault").asBoolean());
            assertEquals("fallback", extractions.get(1).get("value").asText());
            assertTrue(!bodies.get(0).contains(sentinel));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void uploadsControlledMethodRequestBodyAndResponseBodyEvidence() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            JmeterPlan plan = new JmeterPlan("plan-1", "step-1", "https://test.local", "POST", "/orders",
                    List.of(), List.of(), JmeterBody.json(json.readTree("{\"name\":\"demo\"}")),
                    java.util.Map.of(), List.of());
            new JtlResultUploader("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .upload(UUID.randomUUID(), List.of(new JtlSample("step-1", 12, 201, "Created", true, "",
                            "https://test.local/orders", "[]", "{\"id\":1}")), plan);

            JsonNode body = json.readTree(bodies.get(0));
            assertEquals("POST", body.get("requestSummary").get("method").asText());
            assertEquals("demo", body.get("requestSummary").get("body").get("name").asText());
            assertEquals(1, body.get("responseSummary").get("body").get("id").asInt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesEachConfiguredAssertionWhenTheJmeterSamplePasses() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            JmeterPlan plan = new JmeterPlan("plan-1", "step-1", "https://test.local", "GET", "/orders",
                    List.of(), List.of(new JmeterParameter("X-Trace", "trace-1", true),
                            new JmeterParameter("Authorization", "${__autotestSecret(secret-file)}", true)),
                    JmeterBody.none(), java.util.Map.of(), List.of(
                            new JmeterAssertion("STATUS", "EQUALS", "", json.readTree("200")),
                            new JmeterAssertion("JSON_PATH", "EQUALS", "$.ok", json.readTree("true"))));
            new JtlResultUploader("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .upload(UUID.randomUUID(), List.of(new JtlSample("step-1", 12, 200, "OK", true, "",
                            "https://test.local/orders", "[]", "{\"ok\":true}", "", "",
                            "HTTP/1.1 200 OK\nContent-Type: application/json\nX-Trace: trace-1\nSet-Cookie: sid=secret",
                            List.of())), plan);

            JsonNode assertions = json.readTree(bodies.get(0)).get("assertions");
            assertEquals(2, assertions.size());
            assertEquals("STATUS", assertions.get(0).get("type").asText());
            assertEquals("JSON_PATH", assertions.get(1).get("type").asText());
            assertEquals(0, assertions.get(0).get("ruleIndex").asInt());
            assertEquals(1, assertions.get(1).get("ruleIndex").asInt());
            assertTrue(assertions.get(0).get("passed").asBoolean());
            assertTrue(assertions.get(1).get("passed").asBoolean());
            assertEquals(200, assertions.get(0).get("actual").asInt());
            assertTrue(assertions.get(1).get("actual").asBoolean());
            assertEquals(200, assertions.get(0).get("expected").asInt());
            assertEquals("trace-1", json.readTree(bodies.get(0)).get("requestSummary").get("headers").get("X-Trace").asText());
            assertEquals("***", json.readTree(bodies.get(0)).get("requestSummary").get("headers").get("Authorization").asText());
            assertEquals("application/json", json.readTree(bodies.get(0)).get("responseSummary").get("headers").get("Content-Type").asText());
            assertEquals("***", json.readTree(bodies.get(0)).get("responseSummary").get("headers").get("Set-Cookie").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void masksSensitiveJsonPathActualBeforeUpload() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            JmeterPlan plan = new JmeterPlan("plan-1", "step-1", "https://test.local", "GET", "/orders",
                    List.of(), List.of(), JmeterBody.none(), java.util.Map.of(), List.of(
                            new JmeterAssertion("JSON_PATH", "EXISTS", "$.access_token", json.readTree("true")),
                            new JmeterAssertion("JMES_PATH", "EXISTS", "access_token", json.readTree("true"))));
            new JtlResultUploader("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .upload(UUID.randomUUID(), List.of(new JtlSample("step-1", 12, 200, "OK", true, "",
                            "https://test.local/orders", "[]", "{\"access_token\":\"sentinel\"}")), plan);

            JsonNode body = json.readTree(bodies.get(0));
            assertEquals("***", body.get("assertions").get(0).get("actual").asText());
            assertEquals("***", body.get("assertions").get(1).get("actual").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void masksKnownSensitiveValuesInAssertionMessageAndErrorSummary() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            String sentinel = "f1-09-sensitive-sentinel";
            JmeterPlan plan = new JmeterPlan("plan-1", "step-1", "https://test.local", "GET", "/orders",
                    List.of(), List.of(), JmeterBody.none(), java.util.Map.of(), List.of(
                            new JmeterAssertion("JSON_PATH", "EQUALS", "$.token", json.readTree("\"wrong\""))));
            JtlSample sample = new JtlSample("step-1", 12, 200, "OK", false,
                    "actual token=" + sentinel, "https://test.local/orders", "[]",
                    "{\"token\":\"" + sentinel + "\"}", "", "", "",
                    List.of(new JtlAssertionResult("JSONPath Assertion [step-1]", false,
                            "actual=" + sentinel)));

            new JtlResultUploader("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .upload(UUID.randomUUID(), List.of(sample), plan);

            JsonNode body = json.readTree(bodies.get(0));
            assertTrue(!body.get("assertions").get(0).get("message").asText().contains(sentinel));
            assertTrue(!body.get("errorSummary").get("message").asText().contains(sentinel));
            assertTrue(body.get("assertions").get(0).get("message").asText().contains("***"));
            assertTrue(body.get("errorSummary").get("message").asText().contains("***"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void promotesJmeterTransportMessageToErrorEvidenceWhenFailureMessageIsBlank() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            new JtlResultUploader("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .upload(UUID.randomUUID(), List.of(new JtlSample("step-1", 12, 0,
                            "Non HTTP response code: java.net.UnknownHostException: missing-target", false, "",
                            "http://missing-target:8080/orders")));

            JsonNode body = json.readTree(bodies.get(0));
            assertTrue(body.get("errorSummary").get("message").asText().contains("UnknownHostException"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesConfiguredAssertionTypesAndMixedJmeterStatuses() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            JmeterPlan plan = new JmeterPlan("plan-1", "step-1", "https://test.local", "GET", "/orders",
                    List.of(), List.of(), JmeterBody.none(), java.util.Map.of(), List.of(
                            new JmeterAssertion("STATUS", "EQUALS", "", json.readTree("200")),
                            new JmeterAssertion("JSON_PATH", "EQUALS", "$.ok", json.readTree("true"))));
            JtlSample sample = new JtlSample("step-1", 12, 200, "OK", false,
                    "$.ok expected true", "https://test.local/orders", "[]", "", "", "",
                    "",
                    List.of(new JtlAssertionResult("Status Assertion [step-1]", true, ""),
                            new JtlAssertionResult("JSONPath Assertion [step-1]", false, "$.ok expected true")));

            new JtlResultUploader("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .upload(UUID.randomUUID(), List.of(sample), plan);

            JsonNode assertions = json.readTree(bodies.get(0)).get("assertions");
            assertEquals(2, assertions.size());
            assertEquals("STATUS", assertions.get(0).get("type").asText());
            assertEquals("JSON_PATH", assertions.get(1).get("type").asText());
            assertTrue(assertions.get(0).get("passed").asBoolean());
            assertTrue(!assertions.get(1).get("passed").asBoolean());
            assertEquals("$.ok expected true", assertions.get(1).get("message").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void associatesReorderedJmeterAssertionsByRuleIndexIncludingVariable() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            JmeterPlan plan = new JmeterPlan("plan-1", "step-1", "https://test.local", "GET", "/orders",
                    List.of(), List.of(), JmeterBody.none(), java.util.Map.of("attempts", "3"), List.of(
                            new JmeterAssertion("VARIABLE", "GREATER_THAN", "attempts", json.readTree("2")),
                            new JmeterAssertion("HEADER", "EXISTS", "X-Target", null)));
            JtlSample sample = new JtlSample("step-1", 12, 200, "OK", false,
                    "header failed", "https://test.local/orders", "[]", "{}", "GET", "", "X-Target: value",
                    List.of(new JtlAssertionResult("HEADER Assertion [step-1] ruleIndex=1", false, "missing"),
                            new JtlAssertionResult("VARIABLE Assertion [step-1] ruleIndex=0", true, ""),
                            new JtlAssertionResult("unrelated ruleIndex=99", false, "ignored")));

            new JtlResultUploader("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .upload(UUID.randomUUID(), List.of(sample), plan);

            JsonNode assertions = json.readTree(bodies.get(0)).get("assertions");
            assertEquals(2, assertions.size());
            assertEquals(0, assertions.get(0).get("ruleIndex").asInt());
            assertTrue(assertions.get(0).get("passed").asBoolean());
            assertEquals(1, assertions.get(1).get("ruleIndex").asInt());
            assertTrue(!assertions.get(1).get("passed").asBoolean());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesAllConfiguredAssertionStructuresWithDiagnosticWhenJtlHasNoPerAssertionResults() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            JmeterPlan plan = new JmeterPlan("plan-1", "step-1", "https://test.local", "GET", "/orders",
                    List.of(), List.of(), JmeterBody.none(), java.util.Map.of(), List.of(
                            new JmeterAssertion("STATUS", "EQUALS", "", json.readTree("200")),
                            new JmeterAssertion("JSON_PATH", "EQUALS", "$.ok", json.readTree("true"))));
            JtlSample sample = new JtlSample("step-1", 12, 500, "ERR", false,
                    "status failed\njson failed", "https://test.local/orders");

            new JtlResultUploader("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .upload(UUID.randomUUID(), List.of(sample), plan);

            JsonNode assertions = json.readTree(bodies.get(0)).get("assertions");
            assertEquals(2, assertions.size());
            assertEquals("STATUS", assertions.get(0).get("type").asText());
            assertEquals("JSON_PATH", assertions.get(1).get("type").asText());
            assertTrue(!assertions.get(0).get("passed").asBoolean());
            assertTrue(!assertions.get(1).get("passed").asBoolean());
            assertTrue(assertions.get(0).get("diagnostic").asText().contains("diagnostic"));
            assertTrue(assertions.get(1).get("diagnostic").asText().contains("diagnostic"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void derivesVariableActualsFromPlanAndParsesExactSetCookieName() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            JmeterPlan plan = new JmeterPlan("plan-1", "step-1", "https://test.local", "GET", "/orders",
                    List.of(), List.of(new JmeterParameter("Authorization", "known-variable-secret", true)),
                    JmeterBody.none(), java.util.Map.of("attempts", "3", "accessToken", "variable-secret",
                            "traceValue", "known-variable-secret"),
                    List.of(new JmeterAssertion("VARIABLE", "EQUALS", "attempts", json.readTree("2")),
                            new JmeterAssertion("VARIABLE", "EQUALS", "accessToken", json.readTree("\"wrong\"")),
                            new JmeterAssertion("VARIABLE", "EQUALS", "traceValue", json.readTree("\"wrong\"")),
                            new JmeterAssertion("COOKIE", "EQUALS", "session", json.readTree("\"session-value\""))),
                    List.of(), true, 0, 0, null);
            new JtlResultUploader("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .upload(UUID.randomUUID(), List.of(new JtlSample("step-1", 12, 200, "OK", true, "",
                            "https://test.local/orders", "[]", "{}", "GET", "",
                            "Set-Cookie: session=session-value; Path=/\nSet-Cookie: session-id=wrong-cookie; Path=/",
                            List.of())), plan);

            JsonNode assertions = json.readTree(bodies.get(0)).get("assertions");
            assertEquals("3", assertions.get(0).get("actual").asText());
            assertEquals("***", assertions.get(1).get("actual").asText());
            assertEquals("***", assertions.get(2).get("actual").asText());
            assertEquals("session-value", assertions.get(3).get("actual").asText());
            assertTrue(!bodies.get(0).contains("variable-secret"));
            assertTrue(!bodies.get(0).contains("known-variable-secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void removesSecretFunctionsAndKnownSentinelsFromUploadedEvidencePaths() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/runs", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            String sentinel = "runner-upload-sentinel";
            String secretFunction = "${__autotestSecret(secret-file)}";
            JmeterPlan plan = new JmeterPlan("plan-1", "step-1", "https://test.local", "POST", "/orders",
                    List.of(), List.of(new JmeterParameter("Authorization", sentinel, true),
                            new JmeterParameter("X-Trace", secretFunction, true)),
                    JmeterBody.json(json.readTree("{\"note\":\"" + secretFunction
                            + "\",\"trace\":\"" + sentinel + "\"}")),
                    java.util.Map.of(),
                    List.of(new JmeterAssertion("BODY", "EQUALS", "", json.readTree("\"" + sentinel + "\""))),
                    List.of(new JmeterCookie("session", secretFunction)), true, 0, 0, null);
            JtlSample sample = new JtlSample("step-1", 12, 500, "ERR", false,
                    "failure=" + secretFunction + " " + sentinel, "https://test.local/orders", "[]",
                    "{\"note\":\"" + secretFunction + "\",\"trace\":\"" + sentinel + "\"}", "POST", "",
                    "Set-Cookie: session=" + secretFunction + "; Path=/\nX-Trace: " + sentinel,
                    List.of(new JtlAssertionResult("Body Assertion [step-1]", false,
                            "assertion=" + secretFunction + " " + sentinel)));

            new JtlResultUploader("http://127.0.0.1:" + server.getAddress().getPort(), "runner-token")
                    .upload(UUID.randomUUID(), List.of(sample), plan);

            String payload = bodies.get(0);
            assertTrue(!payload.contains(secretFunction));
            assertTrue(!payload.contains(sentinel));
        } finally {
            server.stop(0);
        }
    }
}
