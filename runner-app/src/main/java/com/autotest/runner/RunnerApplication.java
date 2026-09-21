package com.autotest.runner;

import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Runner 常驻入口：恢复遗留任务后，按固定间隔领取并执行单个任务。 */
public final class RunnerApplication {

    private static final Logger LOGGER = Logger.getLogger(RunnerApplication.class.getName());

    private RunnerApplication() {
    }

    public static void main(String[] args) throws Exception {
        RunnerConfiguration configuration = RunnerConfiguration.from(Map.copyOf(System.getenv()));
        LOGGER.info(() -> "Runner 启动: " + configuration);
        DataSource dataSource = dataSource(configuration);
        RunQueueRepository queue = new RunQueueRepository(dataSource);
        queue.recoverRunning();
        Files.createDirectories(configuration.workRoot());

        RunnerWorker worker = new RunnerWorker(queue, new JmeterPlanCompiler(),
                new JmeterProcessRunner(List.of(configuration.jmeterCommand())),
                new JtlResultUploader(configuration.platformApiUrl(), configuration.callbackToken()),
                new RunCompletionNotifier(configuration.platformApiUrl(), configuration.callbackToken()),
                new PlatformSecretResolver(configuration.platformApiUrl(), configuration.callbackToken()),
                new RunFileMaterializer(configuration.platformApiUrl(), configuration.callbackToken()),
                new ProxyCapabilityClient(configuration.platformApiUrl(), configuration.callbackToken()),
                configuration.workRoot(), configuration.jmeterVersion());
        runLoop(worker, configuration.pollIntervalMillis(),
                new RunnerHeartbeatClient(configuration.platformApiUrl(), configuration.callbackToken(), configuration.runnerId()),
                configuration.runnerVersion(), configuration.jmeterVersion());
    }

    static void runLoop(RunnerWorker worker, long pollIntervalMillis) throws InterruptedException {
        runLoop(worker, pollIntervalMillis, null, null, null);
    }

    static void runLoop(RunnerWorker worker, long pollIntervalMillis, RunnerHeartbeatClient heartbeat,
                        String runnerVersion, String jmeterVersion) throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (heartbeat != null) {
                    try {
                        heartbeat.send(runnerVersion, jmeterVersion, null, 0);
                    } catch (RuntimeException ignored) {
                        LOGGER.log(Level.WARNING, "Runner 心跳发送失败，将在下一周期重试");
                    }
                }
                Optional<RunRecord> result = worker.runOnce();
                if (result.isEmpty()) {
                    Thread.sleep(pollIntervalMillis);
                }
            } catch (RuntimeException exception) {
                // 不输出异常消息和堆栈，避免把执行计划或数据库配置带入日志。
                LOGGER.log(Level.WARNING, "Runner 轮询失败，将在下一周期重试");
                Thread.sleep(pollIntervalMillis);
            }
        }
    }

    private static DataSource dataSource(RunnerConfiguration configuration) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(configuration.databaseUrl());
        dataSource.setUser(configuration.databaseUsername());
        dataSource.setPassword(configuration.databasePassword());
        return dataSource;
    }
}
