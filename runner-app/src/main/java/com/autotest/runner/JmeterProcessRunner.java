package com.autotest.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 启动固定 JMeter CLI，不经 shell，不把非零退出升级为 Runner 进程异常。 */
public final class JmeterProcessRunner {

    private static final long POLL_MILLIS = 100;
    private static final long TERMINATION_GRACE_MILLIS = TimeUnit.SECONDS.toMillis(5);

    private final List<String> commandPrefix;

    public JmeterProcessRunner(List<String> commandPrefix) {
        if (commandPrefix == null || commandPrefix.isEmpty()) {
            throw new IllegalArgumentException("JMeter 命令不能为空");
        }
        this.commandPrefix = List.copyOf(commandPrefix);
    }

    public ProcessResult run(Path workDirectory, Path jmx, Path jtl, Path log) throws IOException, InterruptedException {
        return run(workDirectory, jmx, jtl, log, null, () -> false);
    }

    public ProcessResult run(Path workDirectory, Path jmx, Path jtl, Path log,
                             BooleanSupplier cancelRequested) throws IOException, InterruptedException {
        return run(workDirectory, jmx, jtl, log, null, cancelRequested);
    }

    public ProcessResult run(Path workDirectory, Path jmx, Path jtl, Path log,
        JmeterClientCertificate clientCertificate,
                             BooleanSupplier cancelRequested) throws IOException, InterruptedException {
        Path normalizedWorkDirectory = workDirectory.toAbsolutePath().normalize();
        Files.createDirectories(normalizedWorkDirectory);
        List<String> command = new ArrayList<>(commandPrefix);
        if (clientCertificate != null) {
            // -q populates JMeter properties, but the SSL stack reads these as
            // JVM system properties. Use JMeter's -D form so KeystoreConfig
            // sees the PKCS12 materialized for this run. ProcessBuilder keeps
            // each value as one argument; the password is never logged or
            // written to a properties file.
            command.add("-Djavax.net.ssl.keyStore="
                    + clientCertificate.file().toAbsolutePath().normalize());
            command.add("-Djavax.net.ssl.keyStoreType=PKCS12");
            command.add("-Djavax.net.ssl.keyStorePassword=" + clientCertificate.password());
        }
        // ApprovedDnsResolver must perform a fresh A/AAAA lookup on every
        // redirect hop.  Disable the JDK positive/negative DNS caches in the
        // isolated JMeter JVM; otherwise InetAddress can hide a rebinding
        // behind the first answer even though the sampler revalidates.
        command.add("-Dsun.net.inetaddr.ttl=0");
        command.add("-Dsun.net.inetaddr.negative.ttl=0");
        // XML JTL 临时保存响应头，JtlParser 只读取受控 X-Autotest-Extractions 元数据，
        // 不把原始响应头写入平台报告。
        command.add("-Jjmeter.save.saveservice.output_format=xml");
        command.add("-Jjmeter.save.saveservice.responseHeaders=true");
        command.add("-Jjmeter.save.saveservice.response_data=true");
        command.add("-n");
        command.add("-t");
        command.add(jmx.toString());
        command.add("-l");
        command.add(jtl.toString());
        command.add("-j");
        command.add(log.toString());
        Process process = new ProcessBuilder(command)
                .directory(normalizedWorkDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        return await(process, cancelRequested);
    }

    private static ProcessResult await(Process process, BooleanSupplier cancelRequested)
            throws InterruptedException {
        while (process.isAlive()) {
            try {
                if (cancelRequested.getAsBoolean()) {
                    terminateProcessTree(process);
                    return new ProcessResult(process.exitValue(), true);
                }
                process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                cleanupAfterFailure(process, interrupted);
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (RuntimeException | Error failure) {
                cleanupAfterFailure(process, failure);
                throw failure;
            }
        }
        return new ProcessResult(process.exitValue(), false);
    }

    private static void cleanupAfterFailure(Process process, Throwable failure) {
        try {
            terminateProcessTree(process);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void terminateProcessTree(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            Set<ProcessHandle> tree = processTree(process);
            destroy(tree, false);
            WaitResult graceful = waitForTreeExit(tree, TERMINATION_GRACE_MILLIS);
            interrupted |= graceful.interrupted();
            if (!graceful.exited()) {
                tree.addAll(processTree(process));
                destroy(tree, true);
                WaitResult forced = waitForTreeExit(tree, TERMINATION_GRACE_MILLIS);
                interrupted |= forced.interrupted();
                if (!forced.exited()) {
                    throw new IllegalStateException("无法终止 JMeter 进程树");
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static Set<ProcessHandle> processTree(Process process) {
        Set<ProcessHandle> tree = new LinkedHashSet<>();
        ProcessHandle root = process.toHandle();
        tree.add(root);
        root.descendants().forEach(tree::add);
        return tree;
    }

    private static void destroy(Set<ProcessHandle> tree, boolean forcibly) {
        tree.stream().filter(ProcessHandle::isAlive).forEach(handle -> {
            if (forcibly) handle.destroyForcibly();
            else handle.destroy();
        });
    }

    private static WaitResult waitForTreeExit(Set<ProcessHandle> tree, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        boolean interrupted = false;
        while (tree.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(Math.min(POLL_MILLIS,
                        Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        return new WaitResult(tree.stream().noneMatch(ProcessHandle::isAlive), interrupted);
    }

    private record WaitResult(boolean exited, boolean interrupted) {
    }

}
