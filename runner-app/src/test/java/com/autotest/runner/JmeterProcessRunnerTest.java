package com.autotest.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JmeterProcessRunnerTest {

    @Test
    void returnsNonZeroExitCodeWithoutKillingRunnerProcess() throws Exception {
        Path work = Files.createTempDirectory("f1-08-process-");
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        String classPath = System.getProperty("java.class.path");
        JmeterProcessRunner runner = new JmeterProcessRunner(
                List.of(java, "-cp", classPath, FakeJmeterProcess.class.getName()));

        ProcessResult result = runner.run(work, work.resolve("plan.jmx"), work.resolve("result.jtl"),
                work.resolve("jmeter.log"));

        assertEquals(7, result.exitCode());
    }

    @Test
    void passesClientCertificateAsJmeterSystemPropertiesWithoutPersistingPasswordFile() throws Exception {
        Path work = Files.createTempDirectory("f2-01-process-cert-");
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        String classPath = System.getProperty("java.class.path");
        String password = "certificate-password-sentinel";
        JmeterProcessRunner runner = new JmeterProcessRunner(
                List.of(java, "-cp", classPath, FakeJmeterProcess.class.getName(), "assert-cert",
                        work.resolve("client.p12").toString()));

        ProcessResult result = runner.run(work, work.resolve("plan.jmx"), work.resolve("result.jtl"),
                work.resolve("jmeter.log"),
                new JmeterClientCertificate(work.resolve("client.p12"), password), () -> false);

        assertEquals(0, result.exitCode());
        assertFalse(Files.exists(work.resolve("jmeter-client.properties")));
    }

    @Test
    void cancellationTerminatesWrapperAndIgnoredJavaDescendantBeforeReturningCanceled() throws Exception {
        Assumptions.assumeFalse(isWindows(), "该进程树回归依赖 Linux shell signal 语义");
        Path work = Files.createTempDirectory("f1-08-process-cancel-");
        Path childPidFile = work.resolve("child.pid");
        JmeterProcessRunner runner = new JmeterProcessRunner(ignoredJavaChildCommand(childPidFile));

        long childPid = 0;
        try {
            ProcessResult result = runner.run(work, work.resolve("plan.jmx"), work.resolve("result.jtl"),
                    work.resolve("jmeter.log"), () -> Files.exists(childPidFile));
            childPid = readPid(childPidFile);

            assertTrue(result.canceled());
            assertNotEquals(0, result.exitCode());
            assertProcessExited(childPid);
        } finally {
            destroyIfAlive(childPidFile);
        }
    }

    @Test
    void interruptedRunTerminatesWrapperAndIgnoredJavaDescendantBeforePropagating() throws Exception {
        Assumptions.assumeFalse(isWindows(), "该进程树回归依赖 Linux shell signal 语义");
        Path work = Files.createTempDirectory("f1-08-process-interrupt-");
        Path childPidFile = work.resolve("child.pid");
        JmeterProcessRunner runner = new JmeterProcessRunner(ignoredJavaChildCommand(childPidFile));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                runner.run(work, work.resolve("plan.jmx"), work.resolve("result.jtl"),
                        work.resolve("jmeter.log"));
            } catch (Throwable throwable) {
                failure.set(throwable);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        long childPid = 0;
        try {
            worker.start();
            awaitFile(childPidFile);
            childPid = readPid(childPidFile);
            worker.interrupt();
            worker.join(Duration.ofSeconds(10).toMillis());

            assertFalse(worker.isAlive());
            assertTrue(failure.get() instanceof InterruptedException);
            assertTrue(interrupted.get());
            assertProcessExited(childPid);
        } finally {
            if (worker.isAlive()) worker.interrupt();
            destroyIfAlive(childPidFile);
        }
    }

    @Test
    void cancellationCheckFailureTerminatesWrapperAndIgnoredJavaDescendantBeforePropagating() throws Exception {
        Assumptions.assumeFalse(isWindows(), "该进程树回归依赖 Linux shell signal 语义");
        Path work = Files.createTempDirectory("f1-08-process-cancel-error-");
        Path childPidFile = work.resolve("child.pid");
        JmeterProcessRunner runner = new JmeterProcessRunner(ignoredJavaChildCommand(childPidFile));
        IllegalStateException expected = new IllegalStateException("cancel check failed");
        long childPid = 0;

        try {
            IllegalStateException actual = assertThrows(IllegalStateException.class, () -> runner.run(
                    work, work.resolve("plan.jmx"), work.resolve("result.jtl"), work.resolve("jmeter.log"),
                    () -> {
                        if (Files.exists(childPidFile)) throw expected;
                        return false;
                    }));
            childPid = readPid(childPidFile);

            assertEquals(expected, actual);
            assertProcessExited(childPid);
        } finally {
            destroyIfAlive(childPidFile);
        }
    }

    private static List<String> ignoredJavaChildCommand(Path childPidFile) {
        return List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                FakeJmeterProcess.class.getName(), "spawn-ignored-child", childPidFile.toString());
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(Files.exists(file), "子进程未写入 PID 文件");
    }

    private static long readPid(Path file) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(file)) {
                String value = Files.readString(file).trim();
                if (!value.isEmpty()) return Long.parseLong(value);
            }
            Thread.sleep(20);
        }
        return Long.parseLong(Files.readString(file).trim());
    }

    private static void assertProcessExited(long pid) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "JMeter Java 后代仍在运行: " + pid);
    }

    private static void destroyIfAlive(Path childPidFile) {
        try {
            if (!Files.exists(childPidFile)) return;
            long pid = Long.parseLong(Files.readString(childPidFile).trim());
            ProcessHandle.of(pid).ifPresent(handle -> {
                if (handle.isAlive()) handle.destroyForcibly();
            });
        } catch (Exception ignored) {
            // 测试失败后的兜底清理不能覆盖原始断言。
        }
    }

    public static final class FakeJmeterProcess {
        public static void main(String[] args) {
            if (args.length > 0 && args[0].equals("spawn-ignored-child")) {
                try {
                    Path pidFile = Path.of(args[1]);
                    String child = shellQuote(javaExecutable()) + " -cp "
                            + shellQuote(System.getProperty("java.class.path")) + " "
                            + shellQuote(FakeJmeterProcess.class.getName()) + " sleep";
                    Process process = new ProcessBuilder("sh", "-c", "trap '' TERM; exec " + child).start();
                    Files.writeString(pidFile, Long.toString(process.pid()));
                    System.exit(process.waitFor());
                } catch (Exception exception) {
                    System.exit(11);
                }
            }
            if (args.length > 0 && args[0].equals("sleep")) {
                try {
                    while (true) Thread.sleep(1_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    System.exit(12);
                }
            }
            if (args.length > 0 && args[0].equals("assert-cert")) {
                try {
                    List<String> values = java.util.Arrays.asList(args);
                    if (!values.contains("-Djavax.net.ssl.keyStore="
                                    + Path.of(args[1]).toAbsolutePath().normalize())
                            || !values.contains("-Djavax.net.ssl.keyStoreType=PKCS12")
                            || !values.contains("-Djavax.net.ssl.keyStorePassword=certificate-password-sentinel")
                            || values.contains("-q")) {
                        System.exit(8);
                    }
                } catch (Exception exception) {
                    System.exit(10);
                }
                System.exit(0);
            }
            System.exit(7);
        }

        private static String shellQuote(String value) {
            return "'" + value.replace("'", "'\"'\"'") + "'";
        }
    }
}
