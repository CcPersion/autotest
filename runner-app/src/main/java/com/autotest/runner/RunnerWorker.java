package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** 单 Runner 的一次领取与执行编排；调用方可按固定间隔重复调用 {@link #runOnce()}。 */
public final class RunnerWorker {

    @FunctionalInterface
    interface ClaimOperation {
        Optional<RunRecord> claim(String jmeterVersion);
    }

    @FunctionalInterface
    interface CancelOperation {
        boolean requested(UUID runId);
    }

    @FunctionalInterface
    interface FinishOperation {
        Optional<RunRecord> finish(UUID runId, ProcessResult result, String jmxPath, String jtlPath,
                                    String logPath);
    }

    @FunctionalInterface
    interface PlanCompiler {
        Path compile(JmeterPlan plan, Path output) throws Exception;
    }

    @FunctionalInterface
    interface ProcessExecutor {
        ProcessResult run(Path workDirectory, Path jmx, Path jtl, Path log,
                           JmeterClientCertificate clientCertificate,
                           BooleanSupplier cancelRequested) throws Exception;
    }

    @FunctionalInterface
    interface ResultUploadOperation {
        void upload(UUID runId, java.util.List<JtlSample> samples) throws Exception;

        default void upload(UUID runId, java.util.List<JtlSample> samples, JmeterPlan plan) throws Exception {
            upload(runId, samples);
        }
    }

    @FunctionalInterface
    interface CompletionOperation {
        void notify(RunRecord run) throws Exception;
    }

    private final ClaimOperation claim;
    private final CancelOperation cancellation;
    private final FinishOperation finish;
    private final PlanCompiler compiler;
    private final ProcessExecutor process;
    private final ResultUploadOperation resultUpload;
    private final CompletionOperation completion;
    private final SecretResolver secretResolver;
    private final RunFileMaterializer fileMaterializer;
    private final ProxyCapabilityClient proxyAuthorizer;
    private final Path workRoot;
    private final String jmeterVersion;
    private final ObjectMapper json = new ObjectMapper();

    public RunnerWorker(RunQueueRepository queue, JmeterPlanCompiler compiler,
                        JmeterProcessRunner process, Path workRoot, String jmeterVersion) {
        this(queue::claimPending, queue::isCancelRequested, queue::finish, compiler::compile,
                process::run, null, null, null, null, workRoot, jmeterVersion);
    }

    public RunnerWorker(RunQueueRepository queue, JmeterPlanCompiler compiler,
                        JmeterProcessRunner process, JtlResultUploader uploader,
                        Path workRoot, String jmeterVersion) {
        this(queue::claimPending, queue::isCancelRequested, queue::finish, compiler::compile,
                process::run, uploaderOperation(uploader), null, null, null, workRoot, jmeterVersion);
    }

    public RunnerWorker(RunQueueRepository queue, JmeterPlanCompiler compiler,
                        JmeterProcessRunner process, JtlResultUploader uploader,
                        SecretResolver secretResolver, Path workRoot, String jmeterVersion) {
        this(queue::claimPending, queue::isCancelRequested, queue::finish, compiler::compile,
                process::run, uploaderOperation(uploader), secretResolver,
                null, null, workRoot, jmeterVersion);
    }

    public RunnerWorker(RunQueueRepository queue, JmeterPlanCompiler compiler,
                        JmeterProcessRunner process, JtlResultUploader uploader,
                        RunCompletionNotifier notifier, SecretResolver secretResolver,
                        Path workRoot, String jmeterVersion) {
        this(queue::claimPending, queue::isCancelRequested, queue::finish, compiler::compile,
                process::run, uploaderOperation(uploader), secretResolver,
                notifier == null ? null : notifier::notify, null, workRoot, jmeterVersion);
    }

    public RunnerWorker(RunQueueRepository queue, JmeterPlanCompiler compiler,
                        JmeterProcessRunner process, JtlResultUploader uploader,
                        RunCompletionNotifier notifier, SecretResolver secretResolver,
                        RunFileMaterializer fileMaterializer, Path workRoot, String jmeterVersion) {
        this(queue::claimPending, queue::isCancelRequested, queue::finish, compiler::compile,
                process::run, uploaderOperation(uploader), secretResolver,
                notifier == null ? null : notifier::notify, fileMaterializer, null, workRoot, jmeterVersion);
    }

    public RunnerWorker(RunQueueRepository queue, JmeterPlanCompiler compiler,
                        JmeterProcessRunner process, JtlResultUploader uploader,
                        RunCompletionNotifier notifier, SecretResolver secretResolver,
                        RunFileMaterializer fileMaterializer, ProxyCapabilityClient proxyAuthorizer,
                        Path workRoot, String jmeterVersion) {
        this(queue::claimPending, queue::isCancelRequested, queue::finish, compiler::compile,
                process::run, uploaderOperation(uploader), secretResolver,
                notifier == null ? null : notifier::notify, fileMaterializer, proxyAuthorizer,
                workRoot, jmeterVersion);
    }

    RunnerWorker(ClaimOperation claim, CancelOperation cancellation, FinishOperation finish,
                 PlanCompiler compiler, ProcessExecutor process, Path workRoot, String jmeterVersion) {
        this(claim, cancellation, finish, compiler, process, null, workRoot, jmeterVersion);
    }

    RunnerWorker(ClaimOperation claim, CancelOperation cancellation, FinishOperation finish,
                 PlanCompiler compiler, ProcessExecutor process, ResultUploadOperation resultUpload,
                 Path workRoot, String jmeterVersion) {
        this(claim, cancellation, finish, compiler, process, resultUpload, null, workRoot, jmeterVersion);
    }

    RunnerWorker(ClaimOperation claim, CancelOperation cancellation, FinishOperation finish,
                 PlanCompiler compiler, ProcessExecutor process, ResultUploadOperation resultUpload,
                 SecretResolver secretResolver, Path workRoot, String jmeterVersion) {
        this(claim, cancellation, finish, compiler, process, resultUpload, secretResolver, null, workRoot, jmeterVersion);
    }

    RunnerWorker(ClaimOperation claim, CancelOperation cancellation, FinishOperation finish,
                 PlanCompiler compiler, ProcessExecutor process, ResultUploadOperation resultUpload,
                 SecretResolver secretResolver, CompletionOperation completion,
                 Path workRoot, String jmeterVersion) {
        this(claim, cancellation, finish, compiler, process, resultUpload, secretResolver, completion,
                null, workRoot, jmeterVersion);
    }

    RunnerWorker(ClaimOperation claim, CancelOperation cancellation, FinishOperation finish,
                 PlanCompiler compiler, ProcessExecutor process, ResultUploadOperation resultUpload,
                 SecretResolver secretResolver, CompletionOperation completion,
                 RunFileMaterializer fileMaterializer, Path workRoot, String jmeterVersion) {
        this(claim, cancellation, finish, compiler, process, resultUpload, secretResolver, completion,
                fileMaterializer, null, workRoot, jmeterVersion);
    }

    RunnerWorker(ClaimOperation claim, CancelOperation cancellation, FinishOperation finish,
                 PlanCompiler compiler, ProcessExecutor process, ResultUploadOperation resultUpload,
                 SecretResolver secretResolver, CompletionOperation completion,
                 RunFileMaterializer fileMaterializer, ProxyCapabilityClient proxyAuthorizer,
                 Path workRoot, String jmeterVersion) {
        this.claim = claim;
        this.cancellation = cancellation;
        this.finish = finish;
        this.compiler = compiler;
        this.process = process;
        this.resultUpload = resultUpload;
        this.completion = completion;
        this.secretResolver = secretResolver;
        this.fileMaterializer = fileMaterializer;
        this.proxyAuthorizer = proxyAuthorizer;
        this.workRoot = absoluteDirectory(workRoot);
        this.jmeterVersion = required(jmeterVersion, "JMeter 版本不能为空");
        try {
            Files.createDirectories(this.workRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建 Runner 工作目录", exception);
        }
    }

    private static ResultUploadOperation uploaderOperation(JtlResultUploader uploader) {
        if (uploader == null) return null;
        return new ResultUploadOperation() {
            @Override
            public void upload(UUID runId, List<JtlSample> samples) throws Exception {
                uploader.upload(runId, samples);
            }

            @Override
            public void upload(UUID runId, List<JtlSample> samples, JmeterPlan plan) throws Exception {
                uploader.upload(runId, samples, plan);
            }
        };
    }

    /** 领取并处理一个任务；没有待执行任务时返回空。 */
    public Optional<RunRecord> runOnce() {
        Optional<RunRecord> pending = claim.claim(jmeterVersion);
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        RunRecord run = pending.get();
        Path runDirectory = runDirectory(run.id());
        if (fileMaterializer != null) {
            try {
                JsonNode materialized = fileMaterializer.materialize(run.executionPlan(), run.id(), runDirectory);
                run = new RunRecord(run.id(), run.status(), run.jmeterVersion(), run.startedAt(), run.finishedAt(),
                        run.cancelRequested(), run.exitCode(), run.jmxPath(), run.jtlPath(), run.logPath(), materialized);
            } catch (Exception exception) {
                writeSafeFailureLog(runDirectory.resolve("jmeter.log"), exception);
                Optional<RunRecord> failed = finish.finish(run.id(), new ProcessResult(1, false), null, null,
                        runDirectory.resolve("jmeter.log").toString());
                return Optional.of(failed.orElse(run));
            }
        }
        UUID runId = run.id();
        Path jmx = runDirectory.resolve("plan.jmx");
        Path jtl = runDirectory.resolve("result.jtl");
        Path log = runDirectory.resolve("jmeter.log");
        ProcessResult result;
        SecretFileMaterializer secretFiles = null;
        try {
            if (cancellation.requested(run.id())) {
                result = new ProcessResult(143, true);
            } else {
                UUID projectId = projectId(run.executionPlan());
                if (containsSecretReference(run.executionPlan()) && (secretResolver == null || projectId == null)) {
                    throw new IllegalArgumentException("包含密钥引用的运行缺少安全解析上下文");
                }
                if (secretResolver != null && projectId != null) {
                    secretFiles = new SecretFileMaterializer(runDirectory, projectId, secretResolver);
                }
                List<SuiteExecutionStep> suiteSteps = ExecutionPlanAdapter.suiteSteps(run.executionPlan());
                if (!suiteSteps.isEmpty()) {
                    ScenarioExecutionResult suite = executeSuite(run, runDirectory, secretFiles, suiteSteps);
                    result = suite.result();
                    jmx = suite.jmx();
                    jtl = suite.jtl();
                    log = suite.log();
                } else {
                    List<ScenarioExecutionStep> scenarioSteps = ExecutionPlanAdapter.scenarioSteps(run.executionPlan());
                    if (!scenarioSteps.isEmpty()) {
                    ScenarioExecutionResult scenario = executeScenario(run, runDirectory, secretFiles, scenarioSteps);
                    result = scenario.result();
                    jmx = scenario.jmx();
                    jtl = scenario.jtl();
                    log = scenario.log();
                    } else {
                    List<DataRow> configuredRows = ExecutionPlanAdapter.dataRows(run.executionPlan()).stream()
                            .filter(DataRow::enabled).toList();
                    List<DataRow> rows = configuredRows.isEmpty() ? List.of(DataRow.empty()) : configuredRows;
                    boolean continueOnFailure = continueOnDataRowFailure(run.executionPlan());
                    result = new ProcessResult(0, false);
                    boolean anyFailure = false;
                    int firstFailureExitCode = 1;
                    RunVariableContext runContext = RunVariableContext.fromPlan(run.id().toString(), run.executionPlan());
                    for (int index = 0; index < rows.size(); index++) {
                        DataRow row = rows.get(index);
                        RunVariableContext rowContext = runContext.forRow(row.id(), row.values());
                        try {
                            String suffix = rows.size() == 1 && row.id().equals("default") ? "" : "-row-" + index;
                            jmx = runDirectory.resolve("plan" + suffix + ".jmx");
                            jtl = runDirectory.resolve("result" + suffix + ".jtl");
                            log = runDirectory.resolve("jmeter" + suffix + ".log");
                            JmeterPlan plan = ExecutionPlanAdapter.fromJson(run.executionPlan(), secretFiles, rowContext);
                            plan = authorizeProxy(run, plan);
                            compiler.compile(plan, jmx);
                            ProcessResult current = process.run(runDirectory, jmx, jtl, log, plan.clientCertificate(),
                                    () -> cancellation.requested(runId));
                            if (resultUpload != null && !current.canceled() && Files.isRegularFile(jtl)) {
                                List<JtlSample> samples = new JtlParser().parse(jtl);
                                if (!row.id().equals("default")) {
                                    samples = samples.stream().map(sample -> sample.forDataRow(row.id())).toList();
                                }
                                resultUpload.upload(run.id(), samples, plan);
                            }
                            if (resultUpload != null && !current.canceled() && !Files.isRegularFile(jtl)) {
                                writeSafeFailureLog(log, "RUNNER_JMETER_NO_JTL");
                                if (current.exitCode() == 0) current = new ProcessResult(1, false);
                            }
                            if (!current.canceled() && Files.isRegularFile(jtl)) {
                                current = resultFromJtl(current, new JtlParser().parse(jtl));
                            }
                            result = current;
                            if (current.canceled()) {
                                break;
                            }
                            if (current.exitCode() != 0) {
                                anyFailure = true;
                                firstFailureExitCode = current.exitCode();
                                if (!continueOnFailure) break;
                            }
                        } finally {
                            rowContext.clearExtracted();
                        }
                    }
                    if (anyFailure && !result.canceled()) {
                        result = new ProcessResult(firstFailureExitCode, false);
                    }
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            result = new ProcessResult(130, true);
            writeSafeFailureLog(log, "RUNNER_INTERRUPTED");
        } catch (Exception exception) {
            result = new ProcessResult(1, false);
            writeSafeFailureLog(log, exception);
        } finally {
            if (secretFiles != null) {
                secretFiles.close();
            }
        }
        Optional<RunRecord> completed = finish.finish(run.id(), result, jmx.toString(), jtl.toString(), log.toString());
        if (completion != null && completed.isPresent()) {
            try {
                completion.notify(completed.get());
            } catch (Exception ignored) {
                // 外部通知失败不得改变已保存的运行终态；平台端会在下一次运营任务中重试。
            }
        }
        return completed;
    }

    private ScenarioExecutionResult executeSuite(RunRecord run, Path runDirectory,
                                                  SecretFileMaterializer secretFiles,
                                                  List<SuiteExecutionStep> members) throws Exception {
        Path lastJmx = runDirectory.resolve("suite.jmx");
        Path lastJtl = runDirectory.resolve("suite.jtl");
        Path lastLog = runDirectory.resolve("suite.log");
        for (SuiteExecutionStep member : members) {
            if (!member.enabled()) continue;
            if (cancellation.requested(run.id())) return new ScenarioExecutionResult(
                    new ProcessResult(143, true), lastJmx, lastJtl, lastLog);
            JsonNode memberPlan = ExecutionPlanAdapter.prefixMemberStepIds(member.plan(), member.memberId());
            List<ScenarioExecutionStep> nested = ExecutionPlanAdapter.scenarioSteps(memberPlan);
            ProcessResult result;
            if (!nested.isEmpty()) {
                ScenarioExecutionResult scenario = executeScenario(run, runDirectory, secretFiles, nested);
                result = scenario.result();
                lastJmx = scenario.jmx();
                lastJtl = scenario.jtl();
                lastLog = scenario.log();
            } else {
                String safeId = member.memberId().replaceAll("[^A-Za-z0-9_-]", "_");
                lastJmx = runDirectory.resolve("suite-" + safeId + ".jmx");
                lastJtl = runDirectory.resolve("suite-" + safeId + ".jtl");
                lastLog = runDirectory.resolve("suite-" + safeId + ".log");
                // 每个集合成员都是独立执行单元；兄弟成员的提取值不得进入本成员上下文。
                JmeterPlan plan = ExecutionPlanAdapter.fromJson(member.plan(), secretFiles, Map.of(), Map.of());
                plan = authorizeProxy(run, plan);
                compiler.compile(plan, lastJmx);
                result = process.run(runDirectory, lastJmx, lastJtl, lastLog, plan.clientCertificate(),
                        () -> cancellation.requested(run.id()));
                if (resultUpload != null && !result.canceled()) {
                    List<JtlSample> samples = new JtlParser().parse(lastJtl);
                    resultUpload.upload(run.id(), samples, plan);
                    result = resultFromJtl(result, samples);
                }
                if (resultUpload == null && !result.canceled() && Files.isRegularFile(lastJtl)) {
                    result = resultFromJtl(result, new JtlParser().parse(lastJtl));
                }
            }
            if (result.exitCode() != 0 || result.canceled()) {
                return new ScenarioExecutionResult(result, lastJmx, lastJtl, lastLog);
            }
        }
        return new ScenarioExecutionResult(new ProcessResult(0, false), lastJmx, lastJtl, lastLog);
    }

    private ScenarioExecutionResult executeScenario(RunRecord run, Path runDirectory,
                                                     SecretFileMaterializer secretFiles,
                                                     List<ScenarioExecutionStep> steps) throws Exception {
        RunVariableContext context = scenarioContext(run, steps);
        preflightScenario(steps, context);
        ScenarioRunState state = new ScenarioRunState(runDirectory, context);
        Map<String, List<ScenarioExecutionStep>> children = new LinkedHashMap<>();
        List<ScenarioExecutionStep> mainRoots = new ArrayList<>();
        List<ScenarioExecutionStep> cleanupRoots = new ArrayList<>();
        for (ScenarioExecutionStep step : steps) {
            if (step.parentId() != null && !step.parentId().isBlank()) {
                children.computeIfAbsent(step.parentId(), ignored -> new ArrayList<>()).add(step);
            } else if ("CLEANUP".equals(step.section())) {
                cleanupRoots.add(step);
            } else {
                mainRoots.add(step);
            }
        }
        Comparator<ScenarioExecutionStep> order = Comparator.comparingInt(ScenarioExecutionStep::position)
                .thenComparing(ScenarioExecutionStep::stepId);
        children.values().forEach(value -> value.sort(order));
        mainRoots.sort(order);
        cleanupRoots.sort(order);
        for (ScenarioExecutionStep step : mainRoots) {
            if (state.stopMain) break;
            executeScenarioNode(run, runDirectory, secretFiles, step, children, state, true);
        }
        for (ScenarioExecutionStep step : cleanupRoots) {
            // 清理分区必须独立于取消标志执行；主流程取消后仍要尽力释放测试数据。
            executeScenarioNode(run, runDirectory, secretFiles, step, children, state, false);
        }
        ProcessResult outcome = state.primaryFailure == null ? state.latest : state.primaryFailure;
        if (state.stopMain && outcome.exitCode() == 0) outcome = new ProcessResult(1, false);
        return new ScenarioExecutionResult(outcome, state.lastJmx, state.lastJtl, state.lastLog);
    }

    /**
     * 在场景首个外部步骤执行前检查所有启用步骤。前序 extractor 变量以静态 producer
     * 白名单参与检查，实际步骤仍会在 adapter/SQL/Redis 边界按当前上下文再次检查。
     */
    static void preflightScenario(List<ScenarioExecutionStep> steps, RunVariableContext context) {
        List<ScenarioExecutionStep> ordered = steps.stream()
                .filter(ScenarioExecutionStep::enabled)
                .sorted(Comparator.comparingInt(ScenarioExecutionStep::position)
                        .thenComparing(ScenarioExecutionStep::stepId))
                .toList();
        Set<String> produced = new LinkedHashSet<>();
        for (ScenarioExecutionStep step : ordered) {
            JsonNode plan = step.plan();
            if (plan == null || !plan.isObject()) continue;
            List<DataRow> rows = ExecutionPlanAdapter.dataRows(plan).stream().filter(DataRow::enabled).toList();
            if (rows.size() > 1) {
                throw new IllegalArgumentException("DATA_ROWS_IN_SCENARIO_UNSUPPORTED");
            }
            RunVariableContext stepContext = context.forPlan(plan);
            if (rows.size() == 1) {
                stepContext = stepContext.withDataRow(rows.get(0).id(), rows.get(0).values());
            }
            RunVariableContext.PreflightResult result = stepContext.preflight(
                    plan, "scenarioSteps." + step.stepId(), produced);
            if (!result.valid()) {
                throw new IllegalArgumentException(result.code() + ": " + result.path());
            }
            // 只有主流程中的普通根步骤在“成功后才会到达后续步骤”，其提取值才
            // 能作为静态预检的保守豁免。条件/循环子树和 CLEANUP 都不是必经
            // 路径：它们可能不执行，不能把其中的变量传播给主流程消费者。
            if (isGuaranteedProducer(step)) {
                produced.addAll(extractorVariables(plan));
            }
        }
    }

    private static boolean isGuaranteedProducer(ScenarioExecutionStep step) {
        return step.parentId() == null
                || step.parentId().isBlank()
                ? "MAIN".equals(step.section())
                    && !"CONDITION".equals(step.kind())
                    && !"LOOP".equals(step.kind())
                    && "STOP".equals(step.failureStrategy())
                : false;
    }

    private static Set<String> extractorVariables(JsonNode plan) {
        Set<String> names = new LinkedHashSet<>();
        JsonNode extractors = plan == null ? null : plan.get("extractors");
        if (extractors == null || !extractors.isArray()) return names;
        for (JsonNode extractor : extractors) {
            if (extractor == null || !extractor.isObject()) continue;
            String variable = extractor.path("variable").asText("").strip();
            if (!variable.isBlank()) names.add(variable);
        }
        return names;
    }

    private RunVariableContext scenarioContext(RunRecord run, List<ScenarioExecutionStep> steps) {
        for (ScenarioExecutionStep step : steps) {
            if (step.plan() != null && step.plan().isObject()) {
                return RunVariableContext.fromPlan(run.id().toString(), step.plan());
            }
        }
        return RunVariableContext.fromPlan(run.id().toString(), run.executionPlan());
    }

    private void executeScenarioNode(RunRecord run, Path runDirectory, SecretFileMaterializer secretFiles,
                                     ScenarioExecutionStep step,
                                     Map<String, List<ScenarioExecutionStep>> children,
                                     ScenarioRunState state, boolean main) throws Exception {
        if (!step.enabled()) return;
        ProcessResult result;
        if ("CONDITION".equals(step.kind())) {
            result = executeConditionNode(run, runDirectory, secretFiles, step,
                    children.getOrDefault(step.stepId(), List.of()), children, state, main);
        } else if ("LOOP".equals(step.kind())) {
            result = executeLoopNode(run, runDirectory, secretFiles, step,
                    children.getOrDefault(step.stepId(), List.of()), children, state, main);
        } else {
            ScenarioStepExecution execution = executeWithRetry(run, runDirectory, secretFiles, step, state.context, main);
            state.record(execution);
            result = execution.result();
        }
        if (result.canceled()) state.stopMain = main;
        if (main && result.exitCode() != 0) {
            if (state.primaryFailure == null) state.primaryFailure = result;
            if ("STOP".equals(step.failureStrategy())) state.stopMain = true;
        } else if (!main && result.exitCode() != 0 && state.primaryFailure == null) {
            state.primaryFailure = result;
        }
    }

    private ScenarioStepExecution executeWithRetry(RunRecord run, Path runDirectory,
                                                    SecretFileMaterializer secretFiles,
                                                    ScenarioExecutionStep step,
                                                    RunVariableContext context,
                                                    boolean honorCancellation) throws Exception {
        ScenarioStepExecution execution = null;
        for (int attempt = 1; attempt <= step.maxAttempts(); attempt++) {
            execution = executeScenarioStep(run, runDirectory, secretFiles, step, context, attempt, honorCancellation);
            if (execution.result().exitCode() == 0 || execution.result().canceled() || attempt == step.maxAttempts()) {
                return execution;
            }
            long remaining = step.retryIntervalMillis();
            while (remaining > 0) {
                if (honorCancellation && cancellation.requested(run.id())) {
                    return new ScenarioStepExecution(new ProcessResult(143, true), execution.jmx(), execution.jtl(), execution.log(), Map.of());
                }
                long slice = Math.min(remaining, 100L);
                Thread.sleep(slice);
                remaining -= slice;
            }
        }
        return execution;
    }

    private ProcessResult executeConditionNode(RunRecord run, Path runDirectory,
                                                SecretFileMaterializer secretFiles,
                                                ScenarioExecutionStep step,
                                                List<ScenarioExecutionStep> children,
                                                Map<String, List<ScenarioExecutionStep>> allChildren,
                                                ScenarioRunState state, boolean main) throws Exception {
        long started = System.nanoTime();
        boolean matched;
        try {
            matched = ControlFlowEvaluator.condition(step.plan(), state.context.forPlan(step.plan()));
        } catch (RuntimeException exception) {
            return controlResult(run, step, started, false, exception.getMessage());
        }
        for (ScenarioExecutionStep child : children) {
            boolean selected = matched ? !"ELSE".equals(child.branch()) : "ELSE".equals(child.branch());
            if (selected) executeScenarioNode(run, runDirectory, secretFiles, child, allChildren, state, main);
            if (state.stopMain && main) break;
        }
        if (resultUpload != null) {
            try {
                resultUpload.upload(run.id(), List.of(new JtlSample(step.stepId(), elapsed(started), 0,
                        matched ? "CONDITION_TRUE" : "CONDITION_FALSE", true, "", "", "[]")));
            } catch (Exception exception) {
                return new ProcessResult(1, false);
            }
        }
        return new ProcessResult(0, false);
    }

    private ProcessResult executeLoopNode(RunRecord run, Path runDirectory, SecretFileMaterializer secretFiles,
                                          ScenarioExecutionStep step, List<ScenarioExecutionStep> children,
                                          Map<String, List<ScenarioExecutionStep>> allChildren,
                                          ScenarioRunState state, boolean main) throws Exception {
        String mode = step.plan().path("mode").asText("").toUpperCase(java.util.Locale.ROOT);
        int max;
        List<JsonNode> values;
        int count;
        try {
            max = ControlFlowEvaluator.maxIterations(step.plan());
            values = "LIST".equals(mode) ? ControlFlowEvaluator.listItems(step.plan(), state.context.forPlan(step.plan())) : List.of();
            count = "FIXED".equals(mode) ? step.plan().path("count").asInt(0) : values.size();
            if (!List.of("FIXED", "LIST", "WHILE").contains(mode)) throw new IllegalArgumentException("循环模式不支持: " + mode);
            if ("FIXED".equals(mode) && (count < 1 || count > max)) throw new IllegalArgumentException("固定循环次数不合法");
        } catch (RuntimeException exception) {
            return controlResult(run, step, System.nanoTime(), false, exception.getMessage());
        }
        int iterations = 0;
        while (iterations < max) {
            boolean shouldContinue;
            try {
                shouldContinue = "WHILE".equals(mode)
                        ? ControlFlowEvaluator.condition(step.plan(), state.context.forPlan(step.plan())) : iterations < count;
            } catch (RuntimeException exception) {
                return controlResult(run, step, System.nanoTime(), false, exception.getMessage());
            }
            if (!shouldContinue) break;
            if (main && cancellation.requested(run.id())) return new ProcessResult(143, true);
            RunVariableContext.Overlay overlay = null;
            try {
                if ("LIST".equals(mode)) {
                    String variable = step.plan().path("itemVariable").asText("").strip();
                    RunVariableContext.validateWritableName(variable);
                    overlay = state.context.pushOverlay(Map.of(variable, values.get(iterations).deepCopy()));
                }
                for (ScenarioExecutionStep child : children) {
                    if (!"BODY".equals(child.branch())) continue;
                    executeScenarioNode(run, runDirectory, secretFiles, child, allChildren, state, main);
                    if (state.stopMain && main) break;
                }
            } finally {
                if (overlay != null) overlay.close();
            }
            iterations++;
            if (state.stopMain && main) break;
        }
        if (iterations >= max && "WHILE".equals(mode)) {
            try {
                if (ControlFlowEvaluator.condition(step.plan(), state.context.forPlan(step.plan()))) {
                    return controlResult(run, step, System.nanoTime(), false, "条件循环达到最大次数");
                }
            } catch (RuntimeException exception) {
                return controlResult(run, step, System.nanoTime(), false, exception.getMessage());
            }
        }
        if (resultUpload != null) {
            resultUpload.upload(run.id(), List.of(new JtlSample(step.stepId(), 0, 0,
                    "LOOP_" + mode, true, "", "", "[]")));
        }
        return new ProcessResult(0, false);
    }

    private ProcessResult controlResult(RunRecord run, ScenarioExecutionStep step, long started,
                                        boolean success, String failure) {
        if (resultUpload != null) {
            try {
                resultUpload.upload(run.id(), List.of(new JtlSample(step.stepId(), elapsed(started), 0,
                        step.kind(), success, success ? "" : failure, "", "[]")));
            } catch (Exception ignored) {
                // 结果上传失败由主运行状态处理，不将敏感异常写入报告。
            }
        }
        return new ProcessResult(success ? 0 : 1, false);
    }

    private static long elapsed(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private ScenarioStepExecution executeScenarioStep(RunRecord run, Path runDirectory,
                                                       SecretFileMaterializer secretFiles,
                                                       ScenarioExecutionStep step,
                                                       RunVariableContext context,
                                                       int attempt,
                                                       boolean honorCancellation) throws Exception {
        String safeId = step.stepId().replaceAll("[^A-Za-z0-9_-]", "_");
        Path jmx = runDirectory.resolve("scenario-" + safeId + ".jmx");
        Path jtl = runDirectory.resolve("scenario-" + safeId + ".jtl");
        Path log = runDirectory.resolve("scenario-" + safeId + ".log");
        if (step.kind().equals("WAIT")) {
            long started = System.nanoTime();
            long remaining = step.waitMillis();
            while (remaining > 0) {
                if (honorCancellation && cancellation.requested(run.id())) return new ScenarioStepExecution(new ProcessResult(143, true), jmx, jtl, log, Map.of());
                long slice = Math.min(remaining, 100L);
                Thread.sleep(slice);
                remaining -= slice;
            }
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (resultUpload != null) {
                resultUpload.upload(run.id(), List.of(new JtlSample(step.stepId(), elapsed, 0, "WAIT", true, "", "", "[]").forAttempt(attempt)));
            }
            return new ScenarioStepExecution(new ProcessResult(0), jmx, jtl, log, Map.of());
        }
        if (step.kind().equals("SQL")) {
            JdbcSqlExecutor.Result sql = new JdbcSqlExecutor().execute(step.plan(), secretFiles, context.forPlan(step.plan()));
            if (resultUpload != null) resultUpload.upload(run.id(), List.of(sql.sample().forAttempt(attempt)));
            return new ScenarioStepExecution(new ProcessResult(sql.sample().success() ? 0 : 1), jmx, jtl, log, sql.extracted());
        }
        if (step.kind().equals("REDIS")) {
            RedisCommandExecutor.Result redis = new RedisCommandExecutor().execute(step.plan(), secretFiles, context.forPlan(step.plan()));
            if (resultUpload != null) resultUpload.upload(run.id(), List.of(redis.sample().forAttempt(attempt)));
            return new ScenarioStepExecution(new ProcessResult(redis.sample().success() ? 0 : 1), jmx, jtl, log, redis.extracted());
        }
        List<DataRow> scenarioRows = ExecutionPlanAdapter.dataRows(step.plan()).stream().filter(DataRow::enabled).toList();
        if (scenarioRows.size() > 1) {
            throw new IllegalArgumentException("DATA_ROWS_IN_SCENARIO_UNSUPPORTED");
        }
        RunVariableContext stepContext = context.forPlan(step.plan());
        if (scenarioRows.size() == 1) {
            stepContext = stepContext.withDataRow(scenarioRows.get(0).id(), scenarioRows.get(0).values());
        }
        JmeterPlan plan = ExecutionPlanAdapter.fromJson(step.plan(), secretFiles, stepContext);
        plan = authorizeProxy(run, plan);
        compiler.compile(plan, jmx);
        ProcessResult current = process.run(runDirectory, jmx, jtl, log, plan.clientCertificate(),
                () -> honorCancellation && cancellation.requested(run.id()));
        List<JtlSample> samples = current.canceled() || resultUpload == null ? List.of() : new JtlParser().parse(jtl);
        Map<String, JsonNode> next = extractionValues(samples);
        if (resultUpload != null && !current.canceled()) {
            resultUpload.upload(run.id(), samples.stream().map(sample -> sample.forAttempt(attempt)).toList(), plan);
        }
        if (!current.canceled() && samples.isEmpty() && Files.isRegularFile(jtl)) {
            samples = new JtlParser().parse(jtl);
        }
        if (!current.canceled() && Files.isRegularFile(jtl)) {
            current = resultFromJtl(current, samples);
        }
        return new ScenarioStepExecution(current, jmx, jtl, log, next);
    }

    private static ProcessResult resultFromJtl(ProcessResult processResult, List<JtlSample> samples) {
        if (processResult.exitCode() != 0 || processResult.canceled()) return processResult;
        if (samples == null || samples.isEmpty()) return new ProcessResult(1, false);
        return samples.stream().anyMatch(sample -> !sample.success())
                ? new ProcessResult(1, false) : processResult;
    }

    private JmeterPlan authorizeProxy(RunRecord run, JmeterPlan plan) {
        if (proxyAuthorizer == null || plan.proxy() == null) return plan;
        return proxyAuthorizer.authorize(run.id(), plan);
    }

    private Map<String, JsonNode> extractionValues(List<JtlSample> samples) {
        Map<String, JsonNode> values = new java.util.LinkedHashMap<>();
        for (JtlSample sample : samples) {
            try {
                JsonNode extracted = json.readTree(sample.extractionsJson());
                if (extracted != null && extracted.isArray()) {
                    for (JsonNode item : extracted) {
                        String variable = item.path("variable").asText("");
                        if (item.path("matched").asBoolean(false) && !variable.isBlank() && item.has("value")) {
                            values.put(variable, item.get("value").deepCopy());
                        }
                    }
                }
            } catch (IOException ignored) {
                // 损坏的元数据按未提取处理，不影响主流程的状态汇总。
            }
        }
        return values;
    }

    private record ScenarioExecutionResult(ProcessResult result, Path jmx, Path jtl, Path log) {
    }

    private record ScenarioStepExecution(ProcessResult result, Path jmx, Path jtl, Path log,
                                         Map<String, JsonNode> extracted) {
    }

    private static final class ScenarioRunState {
        private final Map<String, JsonNode> extracted = new LinkedHashMap<>();
        private final RunVariableContext context;
        private ProcessResult primaryFailure;
        private ProcessResult latest = new ProcessResult(0, false);
        private Path lastJmx;
        private Path lastJtl;
        private Path lastLog;
        private boolean stopMain;

        private ScenarioRunState(Path runDirectory, RunVariableContext context) {
            this.context = context;
            this.lastJmx = runDirectory.resolve("scenario.jmx");
            this.lastJtl = runDirectory.resolve("scenario.jtl");
            this.lastLog = runDirectory.resolve("scenario.log");
        }

        private void record(ScenarioStepExecution execution) {
            latest = execution.result();
            lastJmx = execution.jmx();
            lastJtl = execution.jtl();
            lastLog = execution.log();
            extracted.putAll(execution.extracted());
            context.putExtractedAll(execution.extracted());
        }
    }

    private static boolean continueOnDataRowFailure(JsonNode input) {
        JsonNode assetContent = input == null ? null : input.path("assetContent");
        JsonNode plan = assetContent != null && assetContent.isObject() && assetContent.has("baseUrl")
                ? assetContent : input;
        JsonNode options = plan == null ? null : plan.get("dataRowOptions");
        if (options == null || options.isNull() || options.isMissingNode()) return true;
        if (!options.isObject()) throw new IllegalArgumentException("dataRowOptions 必须是对象");
        JsonNode value = options.get("continueOnFailure");
        if (value == null || value.isNull()) return true;
        if (!value.isBoolean()) throw new IllegalArgumentException("continueOnFailure 必须是 boolean");
        return value.asBoolean();
    }

    private Path runDirectory(UUID runId) {
        Path directory = workRoot.resolve(runId.toString()).normalize();
        if (!directory.startsWith(workRoot)) {
            throw new IllegalStateException("运行工作目录越界");
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建运行工作目录", exception);
        }
        return directory;
    }

    private static Path absoluteDirectory(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Runner 工作目录不能为空");
        }
        return path.toAbsolutePath().normalize();
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    static String failureCode(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            for (String code : new String[]{"FILE_DOWNLOAD_FAILED", "DOWNLOAD_CHECKSUM_MISMATCH",
                    "DOWNLOAD_METADATA_INVALID", "DOWNLOAD_PATH_INVALID", "SNAPSHOT_METADATA_MISMATCH",
                    "FILE_SNAPSHOT_NOT_FOUND"}) {
                if (message.contains(code)) return code;
            }
        }
        return "RUNNER_EXECUTION_FAILED";
    }

    private static void writeSafeFailureLog(Path log, String code) {
        writeSafeFailureLog(log, code, null);
    }

    private static void writeSafeFailureLog(Path log, Throwable failure) {
        writeSafeFailureLog(log, failureCode(failure), failure == null ? null : failure.getClass().getSimpleName());
    }

    private static void writeSafeFailureLog(Path log, String code, String failureType) {
        try {
            String type = failureType == null ? "" : " type=" + failureType;
            Files.writeString(log, "RUNNER_FAILURE code=" + code + type
                            + "；为避免敏感信息泄露，未写入执行计划或异常消息。\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 结果状态仍由数据库保存，日志文件不可写不应让 Runner 进程退出。
        }
    }

    private static UUID projectId(JsonNode plan) {
        String value = plan == null ? "" : plan.path("projectId").asText("");
        if (value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("执行计划 projectId 不合法");
        }
    }

    private static boolean containsSecretReference(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return node.textValue().contains("${secret:");
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (containsSecretReference(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsSecretReference(item)) {
                    return true;
                }
            }
        }
        return false;
    }
}
