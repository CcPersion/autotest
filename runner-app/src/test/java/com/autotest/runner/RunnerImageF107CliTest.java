package com.autotest.runner;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定 JMeter 5.6.3 Runner 镜像中的 GET Query 编码回归。 */
@EnabledIfEnvironmentVariable(named = "AUTOTEST_F107_IMAGE", matches = ".+")
class RunnerImageF107CliTest {

    @Test
    void cliEncodesSpecialQueryVariableAsOneDecodedParameter() throws Exception {
        String expected = "a&b=c + 空间/汉字";
        AtomicReference<List<String>> receivedNames = new AtomicReference<>(List.of());
        AtomicReference<List<String>> receivedValues = new AtomicReference<>(List.of());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/query", exchange -> {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            String[] pairs = rawQuery == null ? new String[0] : rawQuery.split("&", -1);
            List<String> names = new ArrayList<>();
            List<String> values = new ArrayList<>();
            for (String pair : pairs) {
                int separator = pair.indexOf('=');
                String rawName = separator < 0 ? pair : pair.substring(0, separator);
                String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
                names.add(URLDecoder.decode(rawName, StandardCharsets.UTF_8));
                values.add(URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
            }
            receivedNames.set(List.copyOf(names));
            receivedValues.set(List.copyOf(values));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        Path root = Files.createTempDirectory("f1-07-cli-");
        Path jmx = root.resolve("query.jmx");
        try {
            int port = server.getAddress().getPort();
            JmeterPlan plan = new JmeterPlan(
                    "f1-07-cli", "f1-07-cli-step", "http://127.0.0.1:" + port,
                    "GET", "/query",
                    List.of(new JmeterParameter("q", "${query}", true)),
                    List.of(), JmeterBody.none(), Map.of("query", expected),
                    List.of(new JmeterAssertion("STATUS", "EQUALS", null,
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree("200"))));
            new JmeterPlanCompiler().compile(plan, jmx);

            String image = System.getenv("AUTOTEST_F107_IMAGE");
            Process process = new ProcessBuilder("docker", "run", "--rm", "--network", "host",
                    "-v", jmx.toAbsolutePath() + ":/tmp/f1-07-query.jmx:ro", image,
                    "sh", "-lc",
                    "version=$(jmeter --version 2>&1); echo \"$version\"; "
                            + "echo \"$version\" | grep -q '5.6.3'; "
                            + "jmeter -n -Jjmeter.save.saveservice.output_format=xml "
                            + "-t /tmp/f1-07-query.jmx -l /tmp/f1-07-query.jtl "
                            + ">/tmp/f1-07-jmeter.log 2>&1; status=$?; "
                            + "cat /tmp/f1-07-jmeter.log; cat /tmp/f1-07-query.jtl; exit $status")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();

            assertEquals(0, exit, "固定 JMeter 5.6.3 CLI 失败：\n" + output);
            assertTrue(output.contains("5.6.3"), "CLI 必须为 JMeter 5.6.3：\n" + output);
            assertTrue(output.contains("<httpSample") && output.contains("rc=\"200\"")
                            && output.contains("s=\"true\""),
                    "CLI JTL 必须包含成功 HTTP 样本：\n" + output);
            assertEquals(List.of("q"), receivedNames.get(), "特殊字符不应拆成额外 Query 参数");
            assertEquals(List.of(expected), receivedValues.get(), "服务端应解码为原始单个参数值");
        } finally {
            server.stop(0);
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException ignored) {
                        // 临时材料清理失败不应覆盖 CLI 断言结果。
                    }
                });
            }
        }
    }
}
