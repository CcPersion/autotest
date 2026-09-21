package com.autotest.runner;

import com.autotest.contracts.network.TargetAllowlist;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jorphan.collections.HashTree;

import javax.xml.parsers.DocumentBuilderFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.security.KeyStore;
import java.util.Arrays;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpServer;
import org.w3c.dom.Element;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JmeterPlanCompilerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void compilesGetQueryWithStableStepIdAndStatusAndJsonExistsAssertions() throws Exception {
        JmeterPlan plan = new JmeterPlan(
                "plan-get",
                "step-get-query",
                "http://127.0.0.1:18081",
                "GET",
                "/orders",
                List.of(new JmeterParameter("tenant", "${tenant}", true)),
                List.of(new JmeterParameter("Accept", "application/json", true)),
                JmeterBody.none(),
                Map.of("tenant", "demo"),
                List.of(
                        new JmeterAssertion("STATUS", "EQUALS", null, mapper.readTree("200")),
                        new JmeterAssertion("JSON_PATH", "EXISTS", "$.orderId", null)));
        Path output = Files.createTempFile("f1-07-get-", ".jmx");

        Path result = new JmeterPlanCompiler().compile(plan, output);

        assertEquals(output, result);
        assertPlanShape(output, "step-get-query", 1, 1, 1, 1, 1);
        String xml = Files.readString(output);
        assertTrue(xml.contains("tenant"));
        assertTrue(xml.contains("${tenant}"));
        assertTrue(xml.contains("HTTPArgument.always_encode\">true</boolProp>"));
        assertFalse(xml.contains("HTTPArgument.always_encode\">false</boolProp>"));
        assertTrue(xml.contains("$.orderId"));
        assertTrue(xml.contains("200"));
        assertFalse(xml.contains("JSR223"));
        assertFalse(xml.contains("BeanShell"));
        assertFalse(xml.contains("JavaSampler"));
        assertFalse(xml.contains("OSProcess"));
    }

    @Test
    void compilesPostJsonWithVariablesAndJsonEqualsAssertion() throws Exception {
        JsonNode body = mapper.readTree("{\"orderId\":\"${orderId}\",\"enabled\":true}");
        JmeterPlan plan = new JmeterPlan(
                "plan-post",
                "step-post-json",
                "http://127.0.0.1:18081",
                "POST",
                "/orders",
                List.of(),
                List.of(new JmeterParameter("Content-Type", "application/json", true)),
                JmeterBody.json(body),
                Map.of("orderId", "1001"),
                List.of(
                        new JmeterAssertion("STATUS", "EQUALS", null, mapper.readTree("201")),
                        new JmeterAssertion("JSON_PATH", "EQUALS", "$.accepted", mapper.readTree("true"))));
        Path output = Files.createTempFile("f1-07-post-", ".jmx");

        new JmeterPlanCompiler().compile(plan, output);

        assertPlanShape(output, "step-post-json", 1, 1, 1, 1, 1);
        String xml = Files.readString(output);
        assertTrue(xml.contains("HTTPSampler.postBodyRaw"));
        assertTrue(xml.contains("HTTPArgument.always_encode\">false</boolProp>"));
        assertTrue(xml.contains("${orderId}"));
        assertTrue(xml.contains("$.accepted"));
        assertTrue(xml.contains("201"));
        assertTrue(xml.contains("Content-Type"));
        assertFalse(xml.contains("JSR223"));
        assertFalse(xml.contains("BeanShell"));
        assertFalse(xml.contains("OSProcess"));
    }

    @Test
    void preservesTargetPolicyPropertiesAfterSaveServiceRoundTrip() throws Exception {
        List<String> allowlist = List.of("target:8080", "172.31.90.10:8080", "missing-target:8080");
        JmeterPlan plan = new JmeterPlan(
                "plan-target-policy-round-trip", "step-target-policy-round-trip",
                "http://target:8080", "GET", "/orders/{orderId}", List.of(), List.of(), JmeterBody.none(),
                Map.of(), List.of(), List.of(), true, 0, 0, null, List.of(), null,
                allowlist, true, true);
        Path output = Files.createTempFile("f4-04-target-policy-round-trip-", ".jmx");

        try {
            new JmeterPlanCompiler().compile(plan, output);

            HashTree tree = SaveService.loadTree(output.toFile());
            GuardedHttpSampler sampler = findGuardedHttpSampler(tree);

            assertTrue(sampler.getPropertyAsBoolean(GuardedHttpSampler.TARGET_POLICY_REQUIRED, false));
            assertTrue(sampler.getPropertyAsBoolean(GuardedHttpSampler.TARGET_DNS_REQUIRED, false));
            assertEquals(allowlist, Arrays.asList(
                    sampler.getPropertyAsString(GuardedHttpSampler.TARGET_ALLOWLIST).split("\\R")));
            assertEquals("http", sampler.getProtocol());
            assertEquals("target", sampler.getDomain());
            assertEquals(8080, sampler.getPort());
            assertEquals("/orders/{orderId}", sampler.getPath());

            Method evaluateTarget = GuardedHttpSampler.class.getDeclaredMethod("evaluateTarget", URL.class);
            evaluateTarget.setAccessible(true);
            URL target = new URL("http", "target", 8080, "/orders/{orderId}");
            TargetAllowlist.Decision decision = (TargetAllowlist.Decision) evaluateTarget.invoke(sampler, target);
            assertTrue(decision.allowed(), () -> "decision=" + decision + ", url=" + target
                    + ", allowlist=" + sampler.getPropertyAsString(GuardedHttpSampler.TARGET_ALLOWLIST)
                    + ", policyRequired=" + sampler.getPropertyAsBoolean(
                            GuardedHttpSampler.TARGET_POLICY_REQUIRED, false)
                    + ", dnsRequired=" + sampler.getPropertyAsBoolean(
                            GuardedHttpSampler.TARGET_DNS_REQUIRED, false)
                    + ", protocol=" + sampler.getProtocol() + ", domain=" + sampler.getDomain()
                    + ", port=" + sampler.getPort() + ", path=" + sampler.getPath());
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void compilesUrlEncodedBodyAsFormArguments() throws Exception {
        JsonNode form = mapper.readTree("[{\"name\":\"username\",\"value\":\"alice\",\"enabled\":true},{\"name\":\"enabled\",\"value\":\"true\",\"enabled\":true}]");
        JmeterPlan plan = new JmeterPlan(
                "plan-form",
                "step-form",
                "http://127.0.0.1:18081",
                "POST",
                "/login",
                List.of(),
                List.of(),
                new JmeterBody("URLENCODED", form),
                Map.of(),
                List.of());
        Path output = Files.createTempFile("f2-01-form-", ".jmx");

        new JmeterPlanCompiler().compile(plan, output);

        String xml = Files.readString(output);
        assertTrue(xml.contains("username"));
        assertTrue(xml.contains("alice"));
        assertTrue(xml.contains("enabled"));
        assertTrue(xml.contains("HTTPSampler.postBodyRaw"));
        assertFalse(xml.contains("[{&quot;name&quot;"));
    }

    @Test
    void executesUrlEncodedEmptyAndDuplicateValuesInStableWireOrder() throws Exception {
        AtomicReference<List<String>> received = new AtomicReference<>(List.of());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/form", exchange -> {
            String raw = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            List<String> pairs = java.util.Arrays.stream(raw.split("&", -1))
                    .map(pair -> java.net.URLDecoder.decode(pair, java.nio.charset.StandardCharsets.UTF_8))
                    .toList();
            received.set(pairs);
            byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        Path jmx = Files.createTempFile("f2-01-urlencoded-values-", ".jmx");
        try {
            JsonNode form = mapper.readTree("""
                    [{"name":"tag","value":"first","enabled":true},
                     {"name":"empty","value":"","enabled":true},
                     {"name":"tag","value":"second","enabled":true}]
                    """);
            JmeterPlan plan = new JmeterPlan(
                    "plan-urlencoded-values", "step-urlencoded-values",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "POST", "/form",
                    List.of(), List.of(), new JmeterBody("URLENCODED", form), Map.of(),
                    List.of(new JmeterAssertion("STATUS", "EQUALS", null, mapper.readTree("200"))));
            new JmeterPlanCompiler().compile(plan, jmx);

            CapturingResultCollector collector = runJmeter(jmx);

            assertEquals(1, collector.samples.size());
            assertTrue(collector.samples.get(0).isSuccessful(), collector.samples.get(0).getResponseMessage());
            assertEquals(List.of("tag=first", "empty=", "tag=second"), received.get());
        } finally {
            server.stop(0);
            Files.deleteIfExists(jmx);
        }
    }

    @Test
    void compilesMultipartBodyWithTextFieldAndFilePart() throws Exception {
        JsonNode multipart = mapper.readTree("{\"fields\":[{\"name\":\"title\",\"value\":\"report\",\"enabled\":true}],\"files\":[{\"name\":\"attachment\",\"path\":\"/tmp/report.txt\",\"mimeType\":\"text/plain\"}]}");
        JmeterPlan plan = new JmeterPlan(
                "plan-multipart",
                "step-multipart",
                "http://127.0.0.1:18081",
                "POST",
                "/upload",
                List.of(),
                List.of(),
                new JmeterBody("MULTIPART", multipart),
                Map.of(),
                List.of());
        Path output = Files.createTempFile("f2-01-multipart-", ".jmx");

        new JmeterPlanCompiler().compile(plan, output);

        String xml = Files.readString(output);
        assertTrue(xml.contains("title"));
        assertTrue(xml.contains("report"));
        assertTrue(xml.contains("/tmp/report.txt"));
        assertTrue(xml.contains("HTTPFileArg"));
    }

    @Test
    void skipsDisabledMultipartFilePart() throws Exception {
        JsonNode multipart = mapper.readTree("{\"fields\":[],\"files\":[{\"name\":\"enabled-file\",\"path\":\"/tmp/enabled.txt\",\"enabled\":true},{\"name\":\"disabled-file\",\"path\":\"/tmp/disabled.txt\",\"enabled\":false}]}");
        JmeterPlan plan = new JmeterPlan(
                "plan-multipart-disabled",
                "step-multipart-disabled",
                "http://127.0.0.1:18081",
                "POST",
                "/upload",
                List.of(),
                List.of(),
                new JmeterBody("MULTIPART", multipart),
                Map.of(),
                List.of());
        Path output = Files.createTempFile("f2-01-multipart-disabled-", ".jmx");

        new JmeterPlanCompiler().compile(plan, output);

        String xml = Files.readString(output);
        assertTrue(xml.contains("enabled-file"));
        assertTrue(xml.contains("/tmp/enabled.txt"));
        assertFalse(xml.contains("disabled-file"));
        assertFalse(xml.contains("/tmp/disabled.txt"));
    }

    @Test
    void compilesCookieRedirectAndTimeoutOptions() throws Exception {
        JmeterPlan plan = new JmeterPlan(
                "plan-options",
                "step-options",
                "https://example.test",
                "PATCH",
                "/orders/1",
                List.of(),
                List.of(),
                JmeterBody.none(),
                Map.of(),
                List.of(),
                List.of(new JmeterCookie("session", "${secret:session}")),
                false,
                1500,
                4500,
                null);
        Path output = Files.createTempFile("f2-01-options-", ".jmx");

        new JmeterPlanCompiler().compile(plan, output);

        String xml = Files.readString(output);
        assertTrue(xml.contains("CookieManager"));
        assertTrue(xml.contains("session"));
        assertTrue(xml.contains("HTTPSampler.follow_redirects"));
        assertTrue(xml.contains("false"));
        assertTrue(xml.contains("HTTPSampler.connect_timeout"));
        assertTrue(xml.contains("1500"));
        assertTrue(xml.contains("HTTPSampler.response_timeout"));
        assertTrue(xml.contains("4500"));
        assertTrue(xml.contains("PATCH"));
    }

    @Test
    void compilesPkcs12KeystoreConfigWithoutWritingCertificatePasswordToJmx() throws Exception {
        Path certificate = Files.createTempFile("f2-01-client-", ".p12");
        JmeterPlan plan = new JmeterPlan(
                "plan-cert", "step-cert", "https://example.test", "GET", "/health",
                List.of(), List.of(), JmeterBody.none(), Map.of(), List.of(), List.of(), true,
                0, 0, null, List.of(), new JmeterClientCertificate(certificate, "password-sentinel"));
        Path output = Files.createTempFile("f2-01-cert-", ".jmx");

        new JmeterPlanCompiler().compile(plan, output);

        String xml = Files.readString(output);
        assertTrue(xml.contains("KeystoreConfig"));
        assertTrue(xml.contains("elementType=\"KeystoreConfig\""));
        assertFalse(xml.contains("password-sentinel"));
        assertFalse(xml.contains(certificate.toString()));
    }

    @Test
    void rejectsMultipartFilePathTraversal() throws Exception {
        JsonNode multipart = mapper.readTree("{\"fields\":[],\"files\":[{\"name\":\"../escape\",\"path\":\"/tmp/report.txt\"}]}");
        JmeterPlan plan = new JmeterPlan("plan-invalid-file", "step-invalid-file", "http://127.0.0.1",
                "POST", "/upload", List.of(), List.of(), new JmeterBody("MULTIPART", multipart),
                Map.of(), List.of());

        assertThrows(IllegalArgumentException.class,
                () -> new JmeterPlanCompiler().compile(plan, Files.createTempFile("f2-01-invalid-file-", ".jmx")));
    }

    @Test
    void compilesJsonPathExtractorWithoutScriptComponents() throws Exception {
        JmeterPlan plan = new JmeterPlan(
                "plan-extract", "step-extract", "http://127.0.0.1:18081", "GET", "/login",
                List.of(), List.of(), JmeterBody.none(), Map.of(), List.of(), List.of(), true, 0, 0, null,
                List.of(new JmeterExtractor("JSON_PATH", "$.token", "accessToken", "missing", true)));
        Path output = Files.createTempFile("f2-03-extract-", ".jmx");

        new JmeterPlanCompiler().compile(plan, output);

        String xml = Files.readString(output);
        assertTrue(xml.contains("JSONPostProcessor"));
        assertTrue(xml.contains("$.token"));
        assertTrue(xml.contains("accessToken"));
        assertTrue(xml.contains("missing"));
        assertTrue(count(xml, "JSONPostProcessor") >= 1);
        assertFalse(xml.contains("JSR223"));
        assertFalse(xml.contains("BeanShell"));
    }

    @Test
    void compilesNativeJmeterExtractorsWithoutScriptComponents() throws Exception {
        JmeterPlan plan = new JmeterPlan(
                "plan-extractors", "step-extractors", "http://127.0.0.1:18081", "GET", "/document",
                List.of(), List.of(), JmeterBody.none(), Map.of(), List.of(), List.of(), true, 0, 0, null,
                List.of(
                        new JmeterExtractor("JMESPATH", "data.token", "jmesToken", "missing", true),
                        new JmeterExtractor("XPATH", "//token/text()", "xmlToken", "missing", true),
                        new JmeterExtractor("REGEX", "token=([^&]+)", "regexToken", "missing", true),
                        new JmeterExtractor("HEADER", "X-Trace-Id", "traceId", "missing", true),
                        new JmeterExtractor("COOKIE", "session", "sessionId", "missing", true)));
        Path output = Files.createTempFile("f2-03-native-extractors-", ".jmx");

        new JmeterPlanCompiler().compile(plan, output);

        String xml = Files.readString(output);
        assertTrue(xml.contains("JMESPathExtractor"));
        assertTrue(xml.contains("XPath2Extractor"));
        assertTrue(xml.contains("RegexExtractor"));
        assertTrue(xml.contains("X-Trace-Id"));
        assertTrue(xml.contains("Set-Cookie"));
        assertEquals(5, count(xml, "Extractor Presence"));
        assertFalse(xml.contains("JSR223"));
        assertFalse(xml.contains("BeanShell"));
    }

    @Test
    void compilesNativeResponseAssertionsWithoutScriptComponents() throws Exception {
        JmeterPlan plan = new JmeterPlan(
                "plan-assertions", "step-assertions", "http://127.0.0.1:18081", "GET", "/orders",
                List.of(), List.of(), JmeterBody.none(), Map.of(), List.of(
                        new JmeterAssertion("JMES_PATH", "CONTAINS", "orders[0].id", mapper.readTree("\"1001\"")),
                        new JmeterAssertion("XPATH", "EXISTS", "//order/id", null),
                        new JmeterAssertion("BODY", "CONTAINS", null, mapper.readTree("\"ok\"")),
                        new JmeterAssertion("HEADER", "MATCHES", null, mapper.readTree("\"X-Trace-Id: [a-z0-9]+\"")),
                        new JmeterAssertion("COOKIE", "NOT_CONTAINS", null, mapper.readTree("\"expired\"")),
                        new JmeterAssertion("SCHEMA", "VALIDATE", null,
                                mapper.readTree("{\"type\":\"object\",\"required\":[\"id\"]}")),
                        new JmeterAssertion("RESPONSE_TIME", "LESS_THAN", null, mapper.readTree("500"))),
                List.of(), true, 0, 0, null);
        Path output = Files.createTempFile("f2-03-assertions-", ".jmx");

        new JmeterPlanCompiler().compile(plan, output);

        String xml = Files.readString(output);
        assertTrue(xml.contains("JMESPathAssertion"));
        assertTrue(xml.contains("XPath2Assertion"));
        assertTrue(xml.contains("ResponseAssertion"));
        assertTrue(xml.contains("JmeterJsonSchemaAssertion"));
        assertTrue(xml.contains("DurationAssertion"));
        assertTrue(xml.contains("orders[0].id"));
        assertTrue(xml.contains("X-Trace-Id"));
        assertFalse(xml.contains("JSR223"));
        assertFalse(xml.contains("BeanShell"));
        assertFalse(xml.contains("JavaSampler"));
    }

    @Test
    void executesVariableNumericJsonNumericAndTargetedHeaderCookieAssertionsInJmeter() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/contract", exchange -> {
            exchange.getResponseHeaders().add("X-Target", "value-7");
            exchange.getResponseHeaders().add("Set-Cookie", "sid=abc; Path=/");
            byte[] body = "{\"count\":7,\"items\":[{\"enabled\":true,\"id\":\"first\"},{\"enabled\":false,\"id\":\"second\"}]}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path jmx = Files.createTempFile("f2-03-contract-", ".jmx");
        try {
            JmeterPlan plan = new JmeterPlan("contract", "contract-step",
                     "http://127.0.0.1:" + server.getAddress().getPort(), "GET", "/contract",
                     List.of(), List.of(), JmeterBody.none(), Map.of("attempts", "3"), List.of(
                     new JmeterAssertion("VARIABLE", "GREATER_THAN", "attempts", mapper.readTree("2")),
                     new JmeterAssertion("JSON_PATH", "GREATER_THAN", "$.count", mapper.readTree("5")),
                     new JmeterAssertion("JSON_PATH", "LESS_THAN", "$.count", mapper.readTree("10")),
                     new JmeterAssertion("JMES_PATH", "GREATER_THAN", "count", mapper.readTree("5")),
                     new JmeterAssertion("JMES_PATH", "LESS_THAN", "count", mapper.readTree("10")),
                     new JmeterAssertion("HEADER", "EXISTS", "X-Target", null),
                     new JmeterAssertion("HEADER", "NOT_EXISTS", "X-Missing", null),
                     new JmeterAssertion("HEADER", "EQUALS", "X-Target", mapper.readTree("\"value-7\"")),
                     new JmeterAssertion("HEADER", "CONTAINS", "X-Target", mapper.readTree("\"value\"")),
                     new JmeterAssertion("HEADER", "NOT_CONTAINS", "X-Target", mapper.readTree("\"expired\"")),
                     new JmeterAssertion("HEADER", "MATCHES", "X-Target", mapper.readTree("\"value-[0-9]+\"")),
                     new JmeterAssertion("COOKIE", "EXISTS", "sid", null),
                     new JmeterAssertion("COOKIE", "NOT_EXISTS", "missing", null),
                     new JmeterAssertion("COOKIE", "EQUALS", "sid", mapper.readTree("\"abc\"")),
                     new JmeterAssertion("COOKIE", "CONTAINS", "sid", mapper.readTree("\"bc\"")),
                     new JmeterAssertion("COOKIE", "NOT_CONTAINS", "sid", mapper.readTree("\"expired\"")),
                     new JmeterAssertion("COOKIE", "MATCHES", "sid", mapper.readTree("\"a.c\""))));
            new JmeterPlanCompiler().compile(plan, jmx);
            CapturingResultCollector collector = runJmeter(jmx);

            assertEquals(1, collector.samples.size());
            SampleResult sample = collector.samples.get(0);
            assertTrue(sample.isSuccessful(), sample.getResponseMessage() + " assertions="
                    + Arrays.stream(sample.getAssertionResults())
                    .map(result -> result.getName() + ":" + result.getFailureMessage())
                    .toList());
             assertEquals(17, Arrays.stream(sample.getAssertionResults()).count());
        } finally {
            server.stop(0);
            Files.deleteIfExists(jmx);
        }
    }

    @Test
    void includesStableRuleIndexesInJmeterAssertionNames() throws Exception {
        JmeterPlan plan = new JmeterPlan(
                "plan-indexed-assertions", "step-indexed-assertions", "http://127.0.0.1:18081", "GET", "/orders",
                List.of(), List.of(), JmeterBody.none(), Map.of(), List.of(
                        new JmeterAssertion("BODY", "CONTAINS", null, mapper.readTree("\"first\"")),
                        new JmeterAssertion("BODY", "CONTAINS", null, mapper.readTree("\"second\""))),
                List.of(), true, 0, 0, null);
        Path output = Files.createTempFile("f2-03-indexed-assertions-", ".jmx");

        new JmeterPlanCompiler().compile(plan, output);

        String xml = Files.readString(output);
        assertTrue(xml.contains("BODY Assertion [step-indexed-assertions] ruleIndex=0"));
        assertTrue(xml.contains("BODY Assertion [step-indexed-assertions] ruleIndex=1"));
    }

    @Test
    void executesGetQueryVariableAsOneDecodedParameterWithSpecialCharacters() throws Exception {
        AtomicReference<List<String>> receivedNames = new AtomicReference<>(List.of());
        AtomicReference<List<String>> receivedValues = new AtomicReference<>(List.of());
        String expected = "a&b=c + 空间/汉字";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/query", exchange -> {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            String[] pairs = rawQuery == null ? new String[0] : rawQuery.split("&", -1);
            var names = new java.util.ArrayList<String>();
            var values = new java.util.ArrayList<String>();
            for (String pair : pairs) {
                int separator = pair.indexOf('=');
                String rawName = separator < 0 ? pair : pair.substring(0, separator);
                String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
                names.add(java.net.URLDecoder.decode(rawName, java.nio.charset.StandardCharsets.UTF_8));
                values.add(java.net.URLDecoder.decode(rawValue, java.nio.charset.StandardCharsets.UTF_8));
            }
            receivedNames.set(List.copyOf(names));
            receivedValues.set(List.copyOf(values));
            byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        Path jmx = Files.createTempFile("f1-07-query-special-", ".jmx");
        try {
            int port = server.getAddress().getPort();
            JmeterPlan plan = new JmeterPlan(
                    "plan-query-special", "step-query-special", "http://127.0.0.1:" + port,
                    "GET", "/query",
                    List.of(new JmeterParameter("q", "${query}", true)),
                    List.of(), JmeterBody.none(), Map.of("query", expected),
                    List.of(new JmeterAssertion("STATUS", "EQUALS", null, mapper.readTree("200"))));
            new JmeterPlanCompiler().compile(plan, jmx);

            CapturingResultCollector collector = runJmeter(jmx);

            assertEquals(1, collector.samples.size());
            assertTrue(collector.samples.get(0).isSuccessful(), collector.samples.get(0).getResponseMessage()
                    + " url=" + collector.samples.get(0).getURL());
            assertEquals(List.of("q"), receivedNames.get(), "特殊字符不应拆成额外 Query 参数");
            assertEquals(List.of(expected), receivedValues.get(), "服务端应解码为原始单个参数值");
        } finally {
            server.stop(0);
            Files.deleteIfExists(jmx);
        }
    }

    @Test
    void executesNativeAssertionsAgainstRealHttpResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payload", exchange -> {
            byte[] body = "{\"data\":{\"id\":\"1001\",\"state\":\"PAID\",\"roles\":[\"qa\",\"dev\"]}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Set-Cookie", "session=abc123; Path=/");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        Path jmx = Files.createTempFile("f2-03-real-", ".jmx");
        try {
            int port = server.getAddress().getPort();
            JmeterPlan plan = new JmeterPlan(
                    "plan-real-assertions", "step-real-assertions", "http://127.0.0.1:" + port,
                    "GET", "/payload", List.of(), List.of(), JmeterBody.none(), Map.of(),
                    List.of(new JmeterAssertion("STATUS", "EQUALS", null, mapper.readTree("200")),
                            new JmeterAssertion("JSON_PATH", "EQUALS", "$.data.id", mapper.readTree("\"1001\"")),
                            new JmeterAssertion("BODY", "CONTAINS", null, mapper.readTree("\"PAID\"")),
                            new JmeterAssertion("SCHEMA", "VALIDATE", null, mapper.readTree(
                                    "{\"type\":\"object\",\"required\":[\"data\"],\"properties\":{\"data\":{\"type\":\"object\",\"required\":[\"id\",\"state\"]}}}")),
                            new JmeterAssertion("RESPONSE_TIME", "LESS_THAN", null, mapper.readTree("5000"))),
                    List.of(), true, 0, 0, null,
                    List.of(new JmeterExtractor("JSON_PATH", "$.data", "payload", "", true),
                            new JmeterExtractor("JMESPATH", "data.state", "jmesState", "", true),
                            new JmeterExtractor("REGEX", "\"state\":\"([A-Z]+)", "regexState", "", true),
                            new JmeterExtractor("HEADER", "Content-Type", "contentType", "", true),
                            new JmeterExtractor("COOKIE", "session", "sessionId", "", true)));
            new JmeterPlanCompiler().compile(plan, jmx);

            HashTree tree = SaveService.loadTree(jmx.toFile());
            CapturingResultCollector collector = JmeterComponentMapper.configure(
                    new CapturingResultCollector(), "ViewResultsFullVisualizer", "ResultCollector");
            tree.add(tree.getArray()[0], collector);
            StandardJMeterEngine engine = new StandardJMeterEngine();
            engine.configure(tree);
            engine.runTest();
            engine.awaitTermination(Duration.ofSeconds(20));

            assertEquals(1, collector.samples.size());
            SampleResult sample = collector.samples.get(0);
            assertTrue(sample.isSuccessful(), sample.getResponseMessage() + " assertions="
                    + java.util.Arrays.stream(sample.getAssertionResults())
                    .map(result -> result.getName() + ":" + result.getFailureMessage())
                    .toList());
            assertEquals("200", sample.getResponseCode());
            assertTrue(sample.getResponseHeaders().contains("X-Autotest-Extractions:"),
                    "JMeter 样本应包含平台提取结果元数据");
            String encoded = java.util.Arrays.stream(sample.getResponseHeaders().split("\\R"))
                    .filter(line -> line.startsWith("X-Autotest-Extractions:"))
                    .findFirst().orElseThrow().substring("X-Autotest-Extractions:".length()).trim();
            String extractionJson = new String(java.util.Base64.getDecoder().decode(encoded),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(extractionJson.contains("\"variable\":\"payload\""));
            assertTrue(extractionJson.contains("\"roles\":[\"qa\",\"dev\"]"),
                    "对象/数组提取值必须以 JSON 结构保留: " + extractionJson);
        } finally {
            server.stop(0);
            Files.deleteIfExists(jmx);
        }
    }

    @Test
    void preservesJmeterDefaultTriStateInRealExtractionMetadata() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing", exchange -> {
            byte[] body = "{\"present\":\"yes\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        Path jmx = Files.createTempFile("f2-03-default-tristate-", ".jmx");
        try {
            int port = server.getAddress().getPort();
            JmeterPlan plan = new JmeterPlan(
                    "plan-default-tristate", "step-default-tristate", "http://127.0.0.1:" + port,
                    "GET", "/missing", List.of(), List.of(), JmeterBody.none(), Map.of(), List.of(),
                    List.of(), true, 0, 0, null,
                    List.of(
                            new JmeterExtractor("JMESPATH", "missing", "unconfigured", null, false, false),
                            new JmeterExtractor("JMESPATH", "missing", "explicitNull", null, false, true),
                            new JmeterExtractor("JMESPATH", "missing", "explicitEmpty", "", false, true),
                            new JmeterExtractor("JMESPATH", "missing", "explicitString", "fallback", false, true)));
            new JmeterPlanCompiler().compile(plan, jmx);

            CapturingResultCollector collector = runJmeter(jmx);

            assertEquals(1, collector.samples.size());
            SampleResult sample = collector.samples.get(0);
            assertTrue(sample.isSuccessful(), sample.getResponseMessage() + " assertions="
                    + java.util.Arrays.stream(sample.getAssertionResults())
                    .map(result -> result.getName() + ":" + result.getFailureMessage())
                    .toList()
                    + " headers=" + sample.getResponseHeaders());
            String encoded = java.util.Arrays.stream(sample.getResponseHeaders().split("\\R"))
                    .filter(line -> line.startsWith("X-Autotest-Extractions:"))
                    .findFirst().orElseThrow().substring("X-Autotest-Extractions:".length()).trim();
            JsonNode extractions = mapper.readTree(new String(java.util.Base64.getDecoder().decode(encoded),
                    java.nio.charset.StandardCharsets.UTF_8));

            JsonNode unconfigured = extractions.get(0);
            assertTrue(!unconfigured.get("usedDefault").asBoolean());
            assertEquals("missing", unconfigured.get("valueType").asText());
            assertFalse(unconfigured.has("value"), extractions.toString());

            JsonNode explicitNull = extractions.get(1);
            assertTrue(explicitNull.get("usedDefault").asBoolean());
            assertTrue(explicitNull.get("value").isNull());
            assertEquals("null", explicitNull.get("valueType").asText());

            JsonNode explicitEmpty = extractions.get(2);
            assertTrue(explicitEmpty.get("usedDefault").asBoolean());
            assertTrue(explicitEmpty.get("value").isTextual(), extractions.toString());
            assertEquals("", explicitEmpty.get("value").asText());
            assertEquals("string", explicitEmpty.get("valueType").asText());

            JsonNode explicitString = extractions.get(3);
            assertTrue(explicitString.get("usedDefault").asBoolean());
            assertEquals("fallback", explicitString.get("value").asText());
            assertEquals("string", explicitString.get("valueType").asText());
        } finally {
            server.stop(0);
            Files.deleteIfExists(jmx);
        }
    }

    @Test
    void blocksRedirectToDisallowedHostBeforeUnauthorizedEndpointRuns() throws Exception {
        AtomicInteger blockedRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://localhost:" + server.getAddress().getPort() + "/blocked?secret=do-not-leak");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/blocked", exchange -> {
            blockedRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        Path jmx = Files.createTempFile("f4-04-redirect-denied-", ".jmx");
        try {
            JmeterPlan plan = new JmeterPlan("redirect-denied", "redirect-denied-step",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "GET", "/start",
                    List.of(), List.of(), JmeterBody.none(), Map.of(), List.of());
            new JmeterPlanCompiler().compile(plan, jmx);

            CapturingResultCollector collector = runJmeter(jmx);

            assertEquals(0, blockedRequests.get());
            assertEquals(1, collector.samples.size());
            SampleResult sample = collector.samples.get(0);
            assertFalse(sample.isSuccessful());
            assertEquals("TARGET_NOT_ALLOWED", sample.getResponseCode());
            assertEquals("TARGET_NOT_ALLOWED", sample.getResponseMessage());
            assertFalse(sample.getResponseMessage().contains("secret=do-not-leak"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(jmx);
        }
    }

    @Test
    void blocksDisallowedTargetOnSecondRedirectHopWithoutCallingIt() throws Exception {
        AtomicInteger blockedRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> redirect(exchange, "/allowed"));
        server.createContext("/blocked", exchange -> {
            blockedRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        server.createContext("/allowed", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://localhost:" + port
                    + "/blocked?token=do-not-leak");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        Path jmx = Files.createTempFile("f4-04-redirect-multi-hop-", ".jmx");
        try {
            JmeterPlan plan = new JmeterPlan("redirect-multi-hop", "redirect-multi-hop-step",
                    "http://127.0.0.1:" + port, "GET", "/start",
                    List.of(), List.of(), JmeterBody.none(), Map.of(), List.of());
            new JmeterPlanCompiler().compile(plan, jmx);

            CapturingResultCollector collector = runJmeter(jmx);

            assertEquals(0, blockedRequests.get());
            assertEquals(1, collector.samples.size());
            assertEquals("TARGET_NOT_ALLOWED", collector.samples.get(0).getResponseCode());
            assertEquals("TARGET_NOT_ALLOWED", collector.samples.get(0).getResponseMessage());
        } finally {
            server.stop(0);
            Files.deleteIfExists(jmx);
        }
    }

    @Test
    void followsRedirectChainWhenEveryHopIsAllowlisted() throws Exception {
        AtomicInteger finalRequests = new AtomicInteger();
        AtomicInteger initialRequests = new AtomicInteger();
        AtomicInteger allowedRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            initialRequests.incrementAndGet();
            redirect(exchange, "/allowed");
        });
        server.createContext("/allowed", exchange -> {
            allowedRequests.incrementAndGet();
            redirect(exchange, "/ok");
        });
        server.createContext("/ok", exchange -> {
            finalRequests.incrementAndGet();
            byte[] body = "redirect-ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        Path jmx = Files.createTempFile("f4-04-redirect-allowed-", ".jmx");
        try {
            int port = server.getAddress().getPort();
            JmeterPlan plan = new JmeterPlan("redirect-allowed", "redirect-allowed-step",
                    "http://127.0.0.1:" + port, "GET", "/start",
                    List.of(), List.of(), JmeterBody.none(), Map.of(), List.of(), List.of(), true,
                    0, 0, null, List.of(), null, List.of("127.0.0.1:" + port), true, true);
            new JmeterPlanCompiler().compile(plan, jmx);

            CapturingResultCollector collector = runJmeter(jmx);

            assertEquals(1, finalRequests.get(), "initial=" + initialRequests.get() + ", allowed=" + allowedRequests.get()
                    + ", samples=" + collector.samples.stream()
                    .map(sample -> sample.getResponseCode() + ":" + sample.getResponseMessage())
                    .toList().toString());
            assertEquals(1, collector.samples.size());
            assertTrue(collector.samples.get(0).isSuccessful(), collector.samples.get(0).getResponseMessage());
            assertEquals("200", collector.samples.get(0).getResponseCode());
            assertTrue(collector.samples.get(0).getResponseHeaders().contains("X-Autotest-Pinned-Address:"),
                    "受控 DNS 连接必须记录最终 pin 地址");
        } finally {
            server.stop(0);
            Files.deleteIfExists(jmx);
        }
    }

    @Test
    void rejectsMissingTargetPolicyBeforeInitialRequest() throws Exception {
        AtomicInteger initialRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/initial", exchange -> {
            initialRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        Path jmx = Files.createTempFile("f4-04-redirect-missing-policy-", ".jmx");
        try {
            int port = server.getAddress().getPort();
            JmeterPlan plan = ExecutionPlanAdapter.fromJson(mapper.readTree("""
                    {"planId":"missing-policy","stepId":"missing-policy-step",
                     "baseUrl":"http://127.0.0.1:%d","method":"GET","urlTemplate":"/initial",
                     "body":{"type":"NONE"}}
                    """.formatted(port)));

            assertThrows(IllegalArgumentException.class,
                    () -> new JmeterPlanCompiler().compile(plan, jmx));
            assertEquals(0, initialRequests.get());
        } finally {
            server.stop(0);
            Files.deleteIfExists(jmx);
        }
    }

    @Test
    void serializesGuardedSamplerWithAutomaticRedirectsDisabledAndNoScriptComponents() throws Exception {
        JmeterPlan plan = new JmeterPlan("redirect-metadata", "redirect-metadata-step",
                "http://127.0.0.1:18081", "GET", "/health",
                List.of(), List.of(), JmeterBody.none(), Map.of(), List.of(), List.of(), true,
                0, 0, null, List.of(), null, List.of("127.0.0.1:18081"), true, true);
        Path output = Files.createTempFile("f4-04-redirect-metadata-", ".jmx");

        new JmeterPlanCompiler().compile(plan, output);

        String xml = Files.readString(output);
        assertTrue(xml.contains("GuardedHttpSampler"));
        assertTrue(xml.contains("DNSCacheManager"));
        assertTrue(xml.contains("HTTPSampler.auto_redirects"));
        assertTrue(xml.contains("<boolProp name=\"HTTPSampler.auto_redirects\">false</boolProp>"));
        assertFalse(xml.contains("JSR223"));
        assertFalse(xml.contains("BeanShell"));
        assertFalse(xml.contains("OSProcess"));
    }

    private static void redirect(com.sun.net.httpserver.HttpExchange exchange, String location) throws java.io.IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    @Test
    void executesHttpsRequestWithPkcs12ClientCertificateAgainstMutualTlsServer() throws Exception {
        Path material = Files.createTempDirectory("f2-01-mtls-");
        String storePassword = "changeit";
        String trustPassword = "trustpass";
        Path serverStore = material.resolve("server.p12");
        Path clientStore = material.resolve("client.p12");
        Path serverTrust = material.resolve("server-trust.p12");
        Path clientTrust = material.resolve("client-trust.p12");
        runKeytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-dname", "CN=localhost", "-storetype", "PKCS12",
                "-keystore", serverStore.toString(), "-storepass", storePassword, "-keypass", storePassword,
                "-noprompt");
        runKeytool("-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-dname", "CN=autotest-client", "-storetype", "PKCS12",
                "-keystore", clientStore.toString(), "-storepass", storePassword, "-keypass", storePassword,
                "-noprompt");
        Path serverCertificate = material.resolve("server.cer");
        Path clientCertificate = material.resolve("client.cer");
        runKeytool("-exportcert", "-alias", "server", "-keystore", serverStore.toString(),
                "-storepass", storePassword, "-rfc", "-file", serverCertificate.toString());
        runKeytool("-exportcert", "-alias", "client", "-keystore", clientStore.toString(),
                "-storepass", storePassword, "-rfc", "-file", clientCertificate.toString());
        runKeytool("-importcert", "-alias", "server", "-file", serverCertificate.toString(),
                "-keystore", clientTrust.toString(), "-storetype", "PKCS12", "-storepass", trustPassword,
                "-noprompt");
        runKeytool("-importcert", "-alias", "client", "-file", clientCertificate.toString(),
                "-keystore", serverTrust.toString(), "-storetype", "PKCS12", "-storepass", trustPassword,
                "-noprompt");

        HttpsServer server = mutualTlsServer(serverStore, storePassword, serverTrust, trustPassword);
        server.createContext("/mtls", exchange -> {
            byte[] body = "client-certificate-ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        Path jmx = Files.createTempFile("f2-01-mtls-", ".jmx");
        String[] propertyNames = {"javax.net.ssl.keyStore", "javax.net.ssl.keyStoreType",
                "javax.net.ssl.keyStorePassword", "javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType",
                "javax.net.ssl.trustStorePassword"};
        String[] previous = Arrays.stream(propertyNames).map(System::getProperty).toArray(String[]::new);
        try {
            System.setProperty("javax.net.ssl.keyStore", clientStore.toString());
            System.setProperty("javax.net.ssl.keyStoreType", "PKCS12");
            System.setProperty("javax.net.ssl.keyStorePassword", storePassword);
            System.setProperty("javax.net.ssl.trustStore", clientTrust.toString());
            System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
            System.setProperty("javax.net.ssl.trustStorePassword", trustPassword);
            JmeterPlan plan = new JmeterPlan(
                    "plan-mtls", "step-mtls", "https://localhost:" + server.getAddress().getPort(),
                    "GET", "/mtls", List.of(), List.of(), JmeterBody.none(), Map.of(),
                    List.of(new JmeterAssertion("STATUS", "EQUALS", null, mapper.readTree("200"))),
                    List.of(), true, 0, 5000, null, List.of(),
                    new JmeterClientCertificate(clientStore, storePassword));
            new JmeterPlanCompiler().compile(plan, jmx);
            CapturingResultCollector collector = runJmeter(jmx);
            assertEquals(1, collector.samples.size());
            SampleResult sample = collector.samples.get(0);
            assertTrue(sample.isSuccessful(), sample.getResponseMessage());
            assertEquals("200", sample.getResponseCode());
            assertTrue(new String(sample.getResponseData(), java.nio.charset.StandardCharsets.UTF_8)
                    .contains("client-certificate-ok"));
        } finally {
            for (int index = 0; index < propertyNames.length; index++) {
                if (previous[index] == null) {
                    System.clearProperty(propertyNames[index]);
                } else {
                    System.setProperty(propertyNames[index], previous[index]);
                }
            }
            server.stop(0);
            Files.deleteIfExists(jmx);
            try (var paths = Files.walk(material)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException ignored) {
                        // 临时测试材料清理失败不应掩盖真正的 TLS 断言结果。
                    }
                });
            }
        }
    }

    private static HttpsServer mutualTlsServer(Path keyStorePath, String keyPassword,
                                                Path trustStorePath, String trustPassword) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, keyPassword.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, keyPassword.toCharArray());
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(trustStorePath)) {
            trustStore.load(input, trustPassword.toCharArray());
        }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters parameters) {
                var sslParameters = getSSLContext().getDefaultSSLParameters();
                sslParameters.setNeedClientAuth(true);
                parameters.setSSLParameters(sslParameters);
            }
        });
        return server;
    }

    private static CapturingResultCollector runJmeter(Path jmx) throws Exception {
        HashTree tree = SaveService.loadTree(jmx.toFile());
        CapturingResultCollector collector = JmeterComponentMapper.configure(
                new CapturingResultCollector(), "ViewResultsFullVisualizer", "ResultCollector");
        tree.add(tree.getArray()[0], collector);
        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(tree);
        engine.runTest();
        engine.awaitTermination(Duration.ofSeconds(20));
        return collector;
    }

    private static GuardedHttpSampler findGuardedHttpSampler(HashTree tree) {
        for (Object item : tree.list()) {
            if (item instanceof GuardedHttpSampler sampler) {
                return sampler;
            }
            HashTree child = tree.getTree(item);
            if (child != null) {
                try {
                    return findGuardedHttpSampler(child);
                } catch (AssertionError ignored) {
                    // Continue searching sibling branches.
                }
            }
        }
        throw new AssertionError("JMX 中未找到 GuardedHttpSampler");
    }

    private static void runKeytool(String... arguments) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        var command = new java.util.ArrayList<String>();
        command.add(executable);
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool 生成测试证书失败");
        }
    }

    private static final class CapturingResultCollector extends ResultCollector {
        private final List<SampleResult> samples = new java.util.ArrayList<>();

        @Override
        public void sampleOccurred(SampleEvent event) {
            samples.add(event.getResult());
        }
    }

    private static void assertPlanShape(Path output, String stepId, int plans, int groups,
                                        int samplers, int headers, int statusAssertions) throws Exception {
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile());
        assertEquals("5.6.3", document.getDocumentElement().getAttribute("jmeter"));
        assertEquals(plans, document.getElementsByTagName("TestPlan").getLength());
        assertEquals(groups, document.getElementsByTagName("ThreadGroup").getLength());
        assertEquals(samplers, document.getElementsByTagName("GuardedHttpSampler").getLength());
        assertEquals(headers, document.getElementsByTagName("HeaderManager").getLength());
        assertEquals(statusAssertions, document.getElementsByTagName("ResponseAssertion").getLength());
        assertEquals(1, document.getElementsByTagName("JSONPathAssertion").getLength());
        assertMetadata((Element) document.getElementsByTagName("TestPlan").item(0), "TestPlanGui", "TestPlan");
        assertMetadata((Element) document.getElementsByTagName("ThreadGroup").item(0), "ThreadGroupGui", "ThreadGroup");
        assertMetadata((Element) document.getElementsByTagName("GuardedHttpSampler").item(0), "HttpTestSampleGui", "GuardedHttpSampler");
        assertMetadata((Element) document.getElementsByTagName("HeaderManager").item(0), "HeaderPanel", "HeaderManager");
        assertMetadata((Element) document.getElementsByTagName("ResponseAssertion").item(0), "AssertionGui", "ResponseAssertion");
        assertMetadata((Element) document.getElementsByTagName("JSONPathAssertion").item(0), "JSONPathAssertionGui", "JSONPathAssertion");
        assertTrue(Files.size(output) > 0);
        assertTrue(Files.readString(output).contains(stepId));
    }

    private static int count(String text, String fragment) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(fragment, offset)) >= 0) {
            count++;
            offset += fragment.length();
        }
        return count;
    }

    private static void assertMetadata(Element element, String guiClass, String testClass) {
        assertEquals(guiClass, element.getAttribute("guiclass"));
        assertEquals(testClass, element.getAttribute("testclass"));
        assertEquals("true", element.getAttribute("enabled"));
    }
}
