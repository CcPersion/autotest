package com.autotest.runner;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定 JMeter 5.6.3 镜像的长请求取消与 wrapper 子树回归。 */
@EnabledIfEnvironmentVariable(named = "AUTOTEST_F108_IMAGE", matches = ".+")
class RunnerImageF108CliTest {

    @Test
    void cliCancelsLongRequestAndLeavesNoJmeterJavaAfterForceKill() throws Exception {
        AtomicBoolean requestStarted = new AtomicBoolean();
        AtomicBoolean releaseRequest = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            requestStarted.set(true);
            try {
                while (!releaseRequest.get()) Thread.sleep(25);
                byte[] body = "released".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();

        Path root = Files.createTempDirectory("f1-08-cli-");
        Files.setPosixFilePermissions(root, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE));
        Path jmx = root.resolve("long.jmx");
        Path cancel = root.resolve("cancel");
        Process process = null;
        try {
            int port = server.getAddress().getPort();
            JmeterPlan plan = new JmeterPlan(
                    "f1-08-cli", "f1-08-cli-step", "http://127.0.0.1:" + port,
                    "GET", "/slow", List.of(), List.of(), JmeterBody.none(), Map.of(), List.of());
            new JmeterPlanCompiler().compile(plan, jmx);

            String script = "set -u; "
                    + "version=$(jmeter --version 2>&1); echo \"$version\"; "
                    + "echo \"$version\" | grep -q '5.6.3' || exit 2; "
                    + "jmeter -n -Jjmeter.save.saveservice.output_format=xml "
                    + "-t /tmp/f1-08/long.jmx -l /tmp/f1-08/result.jtl "
                    + ">/tmp/f1-08/jmeter.log 2>&1 & root=$!; "
                    + "while [ ! -f /tmp/f1-08/cancel ]; do "
                    + "  kill -0 \"$root\" 2>/dev/null || { cat /tmp/f1-08/jmeter.log; exit 3; }; "
                    + "  sleep 0.05; "
                    + "done; "
                    + "java_pids() { ps -eo pid=,ppid=,args= | awk -v root=\"$root\" "
                    + "'$1 != root && /\\/opt\\/java\\/openjdk\\/bin\\/java / && /ApacheJMeter.jar/ {print $1}'; }; "
                    + "java_before=$(java_pids); "
                    + "test -n \"$java_before\" || { cat /tmp/f1-08/jmeter.log; exit 4; }; "
                    + "echo J_METER_JAVA_BEFORE_TERM=$java_before; "
                    + "kill -TERM \"$root\" 2>/dev/null || true; "
                    + "for i in $(seq 1 100); do kill -0 \"$root\" 2>/dev/null || break; sleep 0.05; done; "
                    + "for pid in $java_before; do "
                    + "  if kill -0 \"$pid\" 2>/dev/null; then echo RESIDUAL_JMETER_JAVA=$pid; kill -KILL \"$pid\"; fi; "
                    + "done; "
                    + "for i in $(seq 1 100); do remaining=$(java_pids); test -z \"$remaining\" && break; sleep 0.05; done; "
                    + "remaining=$(java_pids); test -z \"$remaining\" || { echo RESIDUAL_JMETER_JAVA_AFTER_FORCE=$remaining; exit 5; }; "
                    + "echo F1_08_CLI_PROCESS_TREE_CLEAN";
            String image = System.getenv("AUTOTEST_F108_IMAGE");
            process = new ProcessBuilder("docker", "run", "--rm", "--network", "host",
                    "-v", root.toAbsolutePath() + ":/tmp/f1-08:rw", image,
                    "sh", "-lc", script)
                    .redirectErrorStream(true)
                    .start();

            if (!await(requestStarted, Duration.ofSeconds(15))) {
                Files.writeString(cancel, "diagnostic-cancel");
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "诊断用 CLI 进程未结束");
                String diagnostic = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(requestStarted.get(), "长请求未进入服务端，取消回归无效：\n" + diagnostic);
            }
            Files.writeString(cancel, "cancel");
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "真实 CLI 取消未在时限内结束");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), "固定 JMeter 5.6.3 长请求取消失败：\n" + output);
            assertTrue(output.contains("5.6.3"), "CLI 必须为 JMeter 5.6.3：\n" + output);
            assertTrue(output.contains("J_METER_JAVA_BEFORE_TERM="), "未捕获 JMeter Java 后代：\n" + output);
            assertTrue(output.contains("F1_08_CLI_PROCESS_TREE_CLEAN"),
                    "强杀后必须确认无 JMeter Java 残留：\n" + output);
        } finally {
            releaseRequest.set(true);
            if (process != null && process.isAlive()) process.destroyForcibly();
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

    private static boolean await(AtomicBoolean value, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!value.get() && System.nanoTime() < deadline) Thread.sleep(25);
        return value.get();
    }
}
