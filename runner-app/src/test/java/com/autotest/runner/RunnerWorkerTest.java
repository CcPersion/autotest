package com.autotest.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerWorkerTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void materializerFailureMapsToFixedSafeCodeWithoutExposingDetails() {
        assertEquals("FILE_DOWNLOAD_FAILED",
                RunnerWorker.failureCode(new IllegalArgumentException("FILE_DOWNLOAD_FAILED: token=secret")));
        assertEquals("RUNNER_EXECUTION_FAILED",
                RunnerWorker.failureCode(new IllegalArgumentException("sensitive implementation detail")));
    }

    @Test
    void claimsCompilesExecutesAndFinishesOneRunInRunScopedDirectory() throws Exception {
        UUID id = UUID.randomUUID();
        RunRecord run = run(id, "GET");
        Path root = Files.createTempDirectory("f1-08-worker-");
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        AtomicReference<Path> jmxPath = new AtomicReference<>();

        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run),
                ignored -> false,
                (runId, result, jmx, jtl, log) -> {
                    finished.set(result);
                    return Optional.of(run);
                },
                (plan, output) -> {
                    Files.writeString(output, "<jmeterTestPlan/>");
                    return output;
                },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    jmxPath.set(jmx);
                    return new ProcessResult(0);
                },
                root, "5.6.3");

        assertTrue(worker.runOnce().isPresent());
        assertEquals(0, finished.get().exitCode());
        assertTrue(jmxPath.get().startsWith(root.resolve(id.toString())));
        assertTrue(Files.exists(jmxPath.get()));
    }

    @Test
    void cancellationBeforeCompilationFinishesCanceledWithoutStartingJmeter() throws Exception {
        UUID id = UUID.randomUUID();
        RunRecord run = run(id, "GET");
        Path root = Files.createTempDirectory("f1-08-worker-cancel-");
        AtomicBoolean compiled = new AtomicBoolean();
        AtomicReference<ProcessResult> finished = new AtomicReference<>();

        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run),
                ignored -> true,
                (runId, result, jmx, jtl, log) -> {
                    finished.set(result);
                    return Optional.of(run);
                },
                (plan, output) -> {
                    compiled.set(true);
                    return output;
                },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    throw new AssertionError("取消任务不应启动 JMeter");
                },
                root, "5.6.3");

        worker.runOnce();
        assertFalse(compiled.get());
        assertTrue(finished.get().canceled());
    }

    @Test
    void compilerFailureIsSavedAsFailedAndDoesNotEscapeWorker() throws Exception {
        RunRecord run = run(UUID.randomUUID(), "GET");
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runId, result, jmx, jtl, log) -> {
                    finished.set(result);
                    return Optional.of(run);
                },
                (plan, output) -> { throw new IllegalArgumentException("invalid plan"); },
                (work, jmx, jtl, log, certificate, cancel) -> { throw new AssertionError("不应启动 JMeter"); },
                Files.createTempDirectory("f1-08-worker-failure-"), "5.6.3");

        assertTrue(worker.runOnce().isPresent());
        assertEquals(1, finished.get().exitCode());
        assertFalse(finished.get().canceled());
    }

    @Test
    void doesNotParseOrUploadMissingJtlAfterNonZeroJmeterExit() throws Exception {
        RunRecord run = run(UUID.randomUUID(), "GET");
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        AtomicBoolean uploaded = new AtomicBoolean();
        AtomicReference<Path> logPath = new AtomicReference<>();
        Path root = Files.createTempDirectory("f2-01-worker-no-jtl-");
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runId, result, jmx, jtl, log) -> {
                    finished.set(result);
                    logPath.set(Path.of(log));
                    return Optional.of(run);
                },
                (plan, output) -> { Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> new ProcessResult(1, false),
                (runId, samples) -> uploaded.set(true), root, "5.6.3");

        worker.runOnce();

        assertEquals(1, finished.get().exitCode());
        assertFalse(uploaded.get());
        assertTrue(Files.readString(logPath.get()).contains("RUNNER_JMETER_NO_JTL"));
    }

    @Test
    void uploadsJtlSamplesAfterProcessFinishes() throws Exception {
        RunRecord run = run(UUID.randomUUID(), "GET");
        AtomicBoolean uploaded = new AtomicBoolean();
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runId, result, jmx, jtl, log) -> Optional.of(run),
                (plan, output) -> { Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    Files.writeString(jtl, "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL\n"
                            + "1,5,\"HTTP Request [step-1]\",200,OK,true,,http://test.local/");
                    return new ProcessResult(0);
                },
                (runId, samples) -> {
                    uploaded.set(samples.size() == 1 && samples.get(0).success());
                },
                Files.createTempDirectory("f1-08-worker-upload-"), "5.6.3");

        worker.runOnce();

        assertTrue(uploaded.get());
    }

    @Test
    void treatsAnEmptyJtlAsFailedEvenWhenJmeterExitsZero() throws Exception {
        RunRecord run = run(UUID.randomUUID(), "GET");
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runId, result, jmx, jtl, log) -> {
                    finished.set(result);
                    return Optional.of(run);
                },
                (plan, output) -> { Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    Files.writeString(jtl, "<testResults version=\"1.2\"></testResults>");
                    return new ProcessResult(0, false);
                },
                (runId, samples) -> { }, Files.createTempDirectory("f2-01-worker-empty-jtl-"), "5.6.3");

        worker.runOnce();

        assertEquals(1, finished.get().exitCode());
        assertFalse(finished.get().canceled());
    }

    @Test
    void executesEnabledDataRowsAndUploadsRowQualifiedResults() throws Exception {
        UUID runId = UUID.randomUUID();
        RunRecord run = new RunRecord(runId, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("""
                {"planId":"row-plan","stepId":"step-1","baseUrl":"http://127.0.0.1",
                 "method":"GET","urlTemplate":"/orders/${orderId}","body":{"type":"NONE"},
                 "variables":{"orderId":"case-order"},
                 "dataRows":[
                   {"id":"row-1","enabled":true,"values":{"orderId":1001}},
                   {"id":"row-disabled","enabled":false,"values":{"orderId":1002}},
                   {"id":"row-2","enabled":true,"values":{"orderId":1003}}
                 ],"dataRowOptions":{"continueOnFailure":true}}
                """));
        Path root = Files.createTempDirectory("f2-04-worker-rows-");
        List<JmeterPlan> compiled = new ArrayList<>();
        List<String> uploadedStepIds = new ArrayList<>();
        AtomicReference<ProcessResult> finished = new AtomicReference<>();

        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runIdValue, result, jmx, jtl, log) -> {
                    finished.set(result);
                    return Optional.of(run);
                },
                (plan, output) -> {
                    compiled.add(plan);
                    Files.writeString(output, "jmx");
                    return output;
                },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    Files.writeString(jtl, "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL\n"
                            + "1,5,\"HTTP Request [step-1]\",200,OK,true,,http://test.local/");
                    return new ProcessResult(0);
                },
                (runIdValue, samples) -> uploadedStepIds.add(samples.get(0).stepId()),
                root, "5.6.3");

        worker.runOnce();

        assertEquals(2, compiled.size());
        assertEquals("/orders/1001", compiled.get(0).urlTemplate());
        assertEquals("/orders/1003", compiled.get(1).urlTemplate());
        assertEquals(List.of("step-1#row-1", "step-1#row-2"), uploadedStepIds);
        assertEquals(0, finished.get().exitCode());
    }

    @Test
    void keepsRunFailedWhenAnEarlierDataRowFailsButPolicyContinues() throws Exception {
        UUID runId = UUID.randomUUID();
        RunRecord run = new RunRecord(runId, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("""
                {"planId":"row-failure","stepId":"step-1","baseUrl":"http://127.0.0.1",
                 "method":"GET","urlTemplate":"/orders/${orderId}","body":{"type":"NONE"},
                 "dataRows":[{"id":"r1","enabled":true,"values":{"orderId":1}},
                              {"id":"r2","enabled":true,"values":{"orderId":2}}],
                 "dataRowOptions":{"continueOnFailure":true}}
                """));
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runIdValue, result, jmx, jtl, log) -> { finished.set(result); return Optional.of(run); },
                (plan, output) -> { Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    Files.writeString(jtl, "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL\n"
                            + "1,5,\"HTTP Request [step-1]\",200,OK,true,,http://test.local/");
                    return new ProcessResult(attempts.getAndIncrement() == 0 ? 1 : 0);
                },
                (runIdValue, samples) -> { }, Files.createTempDirectory("f2-04-worker-failed-row-"), "5.6.3");

        worker.runOnce();

        assertEquals(1, finished.get().exitCode());
    }

    @Test
    void rejectsMultipleEnabledDataRowsWhenApiCaseIsReferencedByScenario() throws Exception {
        UUID runId = UUID.randomUUID();
        RunRecord run = new RunRecord(runId, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("""
                {"planId":"scenario-row-reject","scenarioSteps":[
                  {"stepId":"api","kind":"API_CASE","enabled":true,"failureStrategy":"STOP",
                   "plan":{"planId":"api","stepId":"api","baseUrl":"http://127.0.0.1",
                   "method":"GET","urlTemplate":"/orders/${id}","body":{"type":"NONE"},
                   "dataRows":[{"id":"r1","enabled":true,"values":{"id":1}},
                               {"id":"r2","enabled":true,"values":{"id":2}}]}}
                ]}
                """));
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        AtomicInteger processCalls = new AtomicInteger();
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runIdValue, result, jmx, jtl, log) -> { finished.set(result); return Optional.of(run); },
                (plan, output) -> { Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> { processCalls.incrementAndGet(); return new ProcessResult(0); },
                null, Files.createTempDirectory("f2-02-scenario-data-rows-"), "5.6.3");

        worker.runOnce();

        assertEquals(1, finished.get().exitCode());
        assertEquals(0, processCalls.get());
    }

    @Test
    void rejectsUndefinedHttpVariableBeforeProcessTouch() throws Exception {
        RunRecord baseRun = run(UUID.randomUUID(), "GET");
        RunRecord undefinedRun = new RunRecord(baseRun.id(), baseRun.status(), baseRun.jmeterVersion(), baseRun.startedAt(), baseRun.finishedAt(),
                baseRun.cancelRequested(), baseRun.exitCode(), baseRun.jmxPath(), baseRun.jtlPath(), baseRun.logPath(),
                json.readTree("""
                {"planId":"undefined","baseUrl":"http://127.0.0.1","method":"GET",
                 "urlTemplate":"/orders/${notDefined}","body":{"type":"NONE"}}
                """));
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        AtomicInteger processCalls = new AtomicInteger();
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(undefinedRun), ignored -> false,
                (runIdValue, result, jmx, jtl, log) -> { finished.set(result); return Optional.of(undefinedRun); },
                (plan, output) -> { throw new AssertionError("编译前应被预检拒绝"); },
                (work, jmx, jtl, log, certificate, cancel) -> { processCalls.incrementAndGet(); return new ProcessResult(0); },
                null, Files.createTempDirectory("f2-02-undefined-http-"), "5.6.3");

        worker.runOnce();

        assertEquals(1, finished.get().exitCode());
        assertEquals(0, processCalls.get());
    }

    @Test
    void materializesSecretsWithoutPuttingPlaintextIntoCompiledPlanAndCleansFiles() throws Exception {
        UUID id = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        String secret = "runner-worker-secret-sentinel";
        RunRecord run = new RunRecord(id, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("{\"projectId\":\"" + projectId + "\",\"planId\":\"plan-1\","
                        + "\"stepId\":\"step-1\",\"baseUrl\":\"http://127.0.0.1\","
                        + "\"method\":\"GET\",\"urlTemplate\":\"/health\","
                        + "\"headers\":[{\"name\":\"Authorization\","
                        + "\"value\":\"Bearer ${secret:token}\"}],\"body\":{\"type\":\"NONE\"}}"));
        Path root = Files.createTempDirectory("f2-02-worker-secret-");
        AtomicReference<JmeterPlan> compiled = new AtomicReference<>();
        try (SecretFileMaterializer materializer = new SecretFileMaterializer(root, projectId,
                (project, name) -> secret)) {
            JmeterPlan direct = ExecutionPlanAdapter.fromJson(run.executionPlan(), materializer);
            assertTrue(direct.headers().get(0).value().contains("__autotestSecret("));
        }

        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runId, result, jmx, jtl, log) -> Optional.of(run),
                (plan, output) -> {
                    compiled.set(plan);
                    Files.writeString(output, "jmx");
                    return output;
                },
                (work, jmx, jtl, log, certificate, cancel) -> new ProcessResult(0),
                null,
                (project, name) -> {
                    assertEquals(projectId, project);
                    assertEquals("token", name);
                    return secret;
                }, root, "5.6.3");

        assertTrue(worker.runOnce().isPresent());
        String compiledHeader = compiled.get().headers().get(0).value();
            assertTrue(compiledHeader.startsWith("Bearer ${__autotestSecret("));
        assertTrue(compiledHeader.contains(root.resolve(id.toString()).resolve("secret-files").toString()));
        assertTrue(compiledHeader.endsWith(")}"));
        assertFalse(compiledHeader.contains(secret));
        assertFalse(Files.exists(root.resolve(id.toString()).resolve("secret-files")));
    }

    @Test
    void executesScenarioHttpAndWaitStepsInOrderAndPassesExtractedValueForward() throws Exception {
        UUID runId = UUID.randomUUID();
        String extracted = java.util.Base64.getEncoder().encodeToString(
                "[{\"variable\":\"orderId\",\"type\":\"JSON_PATH\",\"matched\":true,\"value\":42}]"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RunRecord run = new RunRecord(runId, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("""
                {"planId":"scenario-plan","scenarioSteps":[
                  {"stepId":"login","kind":"API_CASE","enabled":true,"failureStrategy":"STOP",
                   "plan":{"planId":"login-plan","stepId":"login","baseUrl":"http://127.0.0.1",
                   "method":"GET","urlTemplate":"/login","body":{"type":"NONE"},
                   "extractors":[{"type":"JSON_PATH","expression":"$.orderId","variable":"orderId"}]}},
                  {"stepId":"pause","kind":"WAIT","enabled":true,"waitMillis":1,"failureStrategy":"CONTINUE"},
                  {"stepId":"order","kind":"HTTP","enabled":true,"failureStrategy":"STOP",
                   "plan":{"planId":"order-plan","stepId":"order","baseUrl":"http://127.0.0.1",
                   "method":"GET","urlTemplate":"/orders/${orderId}","body":{"type":"NONE"}}}
                ]}
                """));
        List<JmeterPlan> compiled = new ArrayList<>();
        List<String> uploaded = new ArrayList<>();
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        AtomicReference<Integer> processCount = new AtomicReference<>(0);

        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runIdValue, result, jmx, jtl, log) -> { finished.set(result); return Optional.of(run); },
                (plan, output) -> { compiled.add(plan); Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    int attempt = processCount.getAndSet(processCount.get() + 1);
                    String line = attempt == 0
                            ? "1,5,\"HTTP Request [login]\",200,OK,true,,http://test.local/login,X-Autotest-Extractions: " + extracted
                            : "1,5,\"HTTP Request [order]\",200,OK,true,,http://test.local/orders/42";
                    Files.writeString(jtl, "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL,responseHeaders\n" + line);
                    return new ProcessResult(0);
                },
                (runIdValue, samples) -> uploaded.add(samples.get(0).stepId()),
                Files.createTempDirectory("f2-05-worker-scenario-"), "5.6.3");

        worker.runOnce();

        assertEquals(2, compiled.size());
        assertEquals("/login", compiled.get(0).urlTemplate());
        assertEquals("/orders/42", compiled.get(1).urlTemplate());
        assertEquals(List.of("login", "pause", "order"), uploaded);
        assertEquals(0, finished.get().exitCode());
    }

    @Test
    void preflightsEntireScenarioBeforeTouchingFirstExternalStep() throws Exception {
        UUID runId = UUID.randomUUID();
        RunRecord run = new RunRecord(runId, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("""
                {"planId":"scenario-preflight","scenarioSteps":[
                  {"stepId":"first","kind":"HTTP","enabled":true,"position":0,"failureStrategy":"STOP",
                   "plan":{"planId":"first-plan","stepId":"first","baseUrl":"http://127.0.0.1",
                   "method":"GET","urlTemplate":"/first","body":{"type":"NONE"}}},
                  {"stepId":"second","kind":"HTTP","enabled":true,"position":1,"failureStrategy":"STOP",
                   "plan":{"planId":"second-plan","stepId":"second","baseUrl":"http://127.0.0.1",
                   "method":"GET","urlTemplate":"/orders/${missingAfterFirst}","body":{"type":"NONE"}}}
                ]}
                """));
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        AtomicInteger processCalls = new AtomicInteger();
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runIdValue, result, jmx, jtl, log) -> { finished.set(result); return Optional.of(run); },
                (plan, output) -> { Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    processCalls.incrementAndGet();
                    Files.writeString(jtl, "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL\n"
                            + "1,5,HTTP,200,OK,true,,http://test.local/first");
                    return new ProcessResult(0);
                },
                null, Files.createTempDirectory("f2-02-scenario-preflight-"), "5.6.3");

        worker.runOnce();

        assertEquals(1, finished.get().exitCode());
        assertEquals(0, processCalls.get());
    }

    @Test
    void preflightDoesNotTreatConditionalBranchExtractorAsGuaranteedProducer() throws Exception {
        List<ScenarioExecutionStep> steps = ExecutionPlanAdapter.scenarioSteps(json.readTree("""
                {"scenarioSteps":[
                  {"stepId":"condition","kind":"CONDITION","enabled":true,"position":0,
                   "plan":{"left":"READY","operator":"EQUALS","right":"READY"}},
                  {"stepId":"then-producer","kind":"HTTP","enabled":true,"position":1,
                   "parentId":"condition","branch":"THEN",
                   "plan":{"planId":"producer","baseUrl":"http://127.0.0.1","method":"GET",
                    "urlTemplate":"/producer","body":{"type":"NONE"},
                    "extractors":[{"type":"JSON_PATH","expression":"$.token","variable":"branchToken"}]}},
                  {"stepId":"consumer","kind":"HTTP","enabled":true,"position":2,
                   "plan":{"planId":"consumer","baseUrl":"http://127.0.0.1","method":"GET",
                    "urlTemplate":"/orders/${branchToken}","body":{"type":"NONE"}}}
                ]}
                """));

        assertThrows(IllegalArgumentException.class,
                () -> invokeScenarioPreflight(steps));
    }

    @Test
    void preflightDoesNotTreatCleanupExtractorAsProducerForMainFlow() throws Exception {
        List<ScenarioExecutionStep> steps = ExecutionPlanAdapter.scenarioSteps(json.readTree("""
                {"scenarioSteps":[
                  {"stepId":"cleanup-producer","kind":"HTTP","enabled":true,"section":"CLEANUP","position":0,
                   "plan":{"planId":"cleanup","baseUrl":"http://127.0.0.1","method":"GET",
                    "urlTemplate":"/cleanup","body":{"type":"NONE"},
                    "extractors":[{"type":"JSON_PATH","expression":"$.token","variable":"cleanupToken"}]}},
                  {"stepId":"main-consumer","kind":"HTTP","enabled":true,"section":"MAIN","position":1,
                   "plan":{"planId":"consumer","baseUrl":"http://127.0.0.1","method":"GET",
                    "urlTemplate":"/orders/${cleanupToken}","body":{"type":"NONE"}}}
                ]}
                """));

        assertThrows(IllegalArgumentException.class,
                () -> invokeScenarioPreflight(steps));
    }

    private static void invokeScenarioPreflight(List<ScenarioExecutionStep> steps) throws Exception {
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.empty(), ignored -> false,
                (runId, result, jmx, jtl, log) -> Optional.empty(),
                (plan, output) -> output,
                (work, jmx, jtl, log, certificate, cancel) -> new ProcessResult(0),
                Files.createTempDirectory("f2-02-preflight-test-"), "5.6.3");
        Method method = RunnerWorker.class.getDeclaredMethod("preflightScenario", List.class, RunVariableContext.class);
        method.setAccessible(true);
        try {
            method.invoke(worker, steps, RunVariableContext.builder("run-1").build());
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) throw checked;
            if (cause instanceof Error error) throw error;
            throw exception;
        }
    }

    @Test
    void executesCleanupScenarioStepsAfterMainFailure() throws Exception {
        UUID runId = UUID.randomUUID();
        RunRecord run = new RunRecord(runId, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("""
                {"planId":"scenario-cleanup","scenarioSteps":[
                  {"stepId":"main","kind":"HTTP","enabled":true,"failureStrategy":"STOP","section":"MAIN",
                   "plan":{"planId":"main-plan","stepId":"main","baseUrl":"http://127.0.0.1",
                   "method":"GET","urlTemplate":"/main","body":{"type":"NONE"}}},
                  {"stepId":"cleanup","kind":"HTTP","enabled":true,"failureStrategy":"CONTINUE","section":"CLEANUP",
                   "plan":{"planId":"cleanup-plan","stepId":"cleanup","baseUrl":"http://127.0.0.1",
                   "method":"DELETE","urlTemplate":"/cleanup","body":{"type":"NONE"}}}
                ]}
                """));
        List<JmeterPlan> compiled = new ArrayList<>();
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        AtomicReference<Integer> processCount = new AtomicReference<>(0);
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runIdValue, result, jmx, jtl, log) -> { finished.set(result); return Optional.of(run); },
                (plan, output) -> { compiled.add(plan); Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    int attempt = processCount.getAndSet(processCount.get() + 1);
                    Files.writeString(jtl, "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL\n"
                            + "1,5,\"HTTP Request [" + (attempt == 0 ? "main" : "cleanup") + "]\","
                            + (attempt == 0 ? "500,ERR,false,failed" : "204,No Content,true,") + ",http://test.local/");
                    return new ProcessResult(attempt == 0 ? 1 : 0);
                },
                (runIdValue, samples) -> { }, Files.createTempDirectory("f2-05-worker-cleanup-"), "5.6.3");

        worker.runOnce();

        assertEquals(2, compiled.size());
        assertEquals("/cleanup", compiled.get(1).urlTemplate());
        assertEquals(1, finished.get().exitCode());
    }

    @Test
    void treatsJmeterAssertionFailureAsScenarioFailureEvenWhenProcessExitIsZero() throws Exception {
        UUID runId = UUID.randomUUID();
        RunRecord run = new RunRecord(runId, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("""
                {"planId":"scenario-jmeter-assertion","scenarioSteps":[
                  {"stepId":"first","kind":"HTTP","enabled":true,"section":"MAIN","failureStrategy":"STOP",
                   "plan":{"planId":"first-plan","stepId":"first","baseUrl":"http://127.0.0.1","method":"GET","urlTemplate":"/first","body":{"type":"NONE"}}},
                  {"stepId":"second","kind":"HTTP","enabled":true,"section":"MAIN","failureStrategy":"STOP",
                   "plan":{"planId":"second-plan","stepId":"second","baseUrl":"http://127.0.0.1","method":"GET","urlTemplate":"/second","body":{"type":"NONE"}}}
                ]}
                """));
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runIdValue, result, jmx, jtl, log) -> { finished.set(result); return Optional.of(run); },
                (plan, output) -> { Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    int call = calls.getAndIncrement();
                    Files.writeString(jtl, "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL\n"
                            + "1,5,\"HTTP Request [" + (call == 0 ? "first" : "second") + "]\","
                            + (call == 0 ? "401,Unauthorized,false,JSONPath token missing" : "200,OK,true,")
                            + ",http://test.local/\n");
                    return new ProcessResult(0);
                },
                (runIdValue, samples) -> { }, Files.createTempDirectory("f2-10-worker-assertion-"), "5.6.3");

        worker.runOnce();

        assertEquals(1, calls.get());
        assertEquals(1, finished.get().exitCode());
        assertFalse(finished.get().canceled());
    }

    @Test
    void executesCleanupAfterCancellationWithoutPassingCancelSignalToCleanup() throws Exception {
        UUID runId = UUID.randomUUID();
        RunRecord run = new RunRecord(runId, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("""
                {"planId":"scenario-cancel-cleanup","scenarioSteps":[
                  {"stepId":"main","kind":"HTTP","enabled":true,"failureStrategy":"STOP","section":"MAIN",
                   "plan":{"planId":"main-plan","stepId":"main","baseUrl":"http://127.0.0.1",
                   "method":"GET","urlTemplate":"/main","body":{"type":"NONE"}}},
                  {"stepId":"cleanup","kind":"HTTP","enabled":true,"failureStrategy":"CONTINUE","section":"CLEANUP",
                   "plan":{"planId":"cleanup-plan","stepId":"cleanup","baseUrl":"http://127.0.0.1",
                   "method":"DELETE","urlTemplate":"/cleanup","body":{"type":"NONE"}}}
                ]}
                """));
        List<JmeterPlan> compiled = new ArrayList<>();
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Boolean> cleanupSawCancel = new AtomicReference<>();
        AtomicBoolean cancelRequested = new AtomicBoolean();
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> cancelRequested.get(),
                (runIdValue, result, jmx, jtl, log) -> { finished.set(result); return Optional.of(run); },
                (plan, output) -> { compiled.add(plan); Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    int call = calls.getAndIncrement();
                    if (call == 0) {
                        assertFalse(cancel.getAsBoolean());
                        cancelRequested.set(true);
                        return new ProcessResult(143, true);
                    }
                    cleanupSawCancel.set(cancel.getAsBoolean());
                    Files.writeString(jtl, "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL\n"
                            + "1,5,\"HTTP Request [cleanup]\",204,No Content,true,,http://test.local/cleanup");
                    return new ProcessResult(0);
                },
                (runIdValue, samples) -> { }, Files.createTempDirectory("f2-09-worker-cancel-cleanup-"), "5.6.3");

        worker.runOnce();

        assertEquals(2, compiled.size());
        assertEquals(2, calls.get());
        assertEquals(Boolean.FALSE, cleanupSawCancel.get());
        assertTrue(finished.get().canceled());
        assertEquals(143, finished.get().exitCode());
    }

    @Test
    void cleanupFailureDoesNotReplacePrimaryFailure() throws Exception {
        UUID runId = UUID.randomUUID();
        RunRecord run = new RunRecord(runId, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("""
                {"planId":"scenario-cleanup-failure","scenarioSteps":[
                  {"stepId":"main","kind":"HTTP","enabled":true,"failureStrategy":"STOP","section":"MAIN",
                   "plan":{"planId":"main-plan","stepId":"main","baseUrl":"http://127.0.0.1",
                   "method":"GET","urlTemplate":"/main","body":{"type":"NONE"}}},
                  {"stepId":"cleanup","kind":"HTTP","enabled":true,"failureStrategy":"CONTINUE","section":"CLEANUP",
                   "plan":{"planId":"cleanup-plan","stepId":"cleanup","baseUrl":"http://127.0.0.1",
                   "method":"DELETE","urlTemplate":"/cleanup","body":{"type":"NONE"}}}
                ]}
                """));
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runIdValue, result, jmx, jtl, log) -> { finished.set(result); return Optional.of(run); },
                (plan, output) -> { Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    int call = calls.getAndIncrement();
                    int exitCode = call == 0 ? 7 : 9;
                    Files.writeString(jtl, "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL\n"
                            + "1,5,\"HTTP Request [" + (call == 0 ? "main" : "cleanup") + "]\",500,ERR,false,failed,http://test.local/");
                    return new ProcessResult(exitCode);
                },
                (runIdValue, samples) -> { }, Files.createTempDirectory("f2-09-worker-cleanup-failure-"), "5.6.3");

        worker.runOnce();

        assertEquals(2, calls.get());
        assertEquals(7, finished.get().exitCode());
        assertFalse(finished.get().canceled());
    }

    @Test
    void executesOnlyMatchingConditionBranch() throws Exception {
        UUID runId = UUID.randomUUID();
        RunRecord run = new RunRecord(runId, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("""
                {"planId":"scenario-condition","scenarioSteps":[
                  {"stepId":"condition","kind":"CONDITION","enabled":true,"position":0,"failureStrategy":"STOP",
                   "plan":{"stepId":"condition","left":"${status}","operator":"EQUALS","right":"READY",
                    "variableScopes":{"extracted":{"status":"READY"}}}},
                  {"stepId":"then","kind":"HTTP","enabled":true,"position":1,"parentId":"condition","branch":"THEN","failureStrategy":"STOP",
                   "plan":{"planId":"then-plan","stepId":"then","baseUrl":"http://127.0.0.1","method":"GET","urlTemplate":"/then","body":{"type":"NONE"}}},
                  {"stepId":"else","kind":"HTTP","enabled":true,"position":2,"parentId":"condition","branch":"ELSE","failureStrategy":"STOP",
                   "plan":{"planId":"else-plan","stepId":"else","baseUrl":"http://127.0.0.1","method":"GET","urlTemplate":"/else","body":{"type":"NONE"}}}
                ]}
                """));
        List<String> compiled = new ArrayList<>();
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runIdValue, result, jmx, jtl, log) -> { finished.set(result); return Optional.of(run); },
                (plan, output) -> { compiled.add(plan.urlTemplate()); Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    Files.writeString(jtl, "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL\n"
                            + "1,5,HTTP,200,OK,true,,http://test.local/then");
                    return new ProcessResult(0);
                },
                (runIdValue, samples) -> { }, Files.createTempDirectory("f2-08-worker-condition-"), "5.6.3");

        worker.runOnce();

        assertEquals(List.of("/then"), compiled);
        assertEquals(0, finished.get().exitCode());
    }

    @Test
    void retriesFailedScenarioStepWithAttemptQualifiedResult() throws Exception {
        UUID runId = UUID.randomUUID();
        RunRecord run = new RunRecord(runId, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("""
                {"planId":"scenario-retry","scenarioSteps":[
                  {"stepId":"retry-step","kind":"HTTP","enabled":true,"position":0,"failureStrategy":"RETRY",
                   "retry":{"maxAttempts":2,"intervalMillis":0},
                   "plan":{"planId":"retry-plan","stepId":"retry-step","baseUrl":"http://127.0.0.1","method":"GET","urlTemplate":"/retry","body":{"type":"NONE"}}}
                ]}
                """));
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        AtomicReference<Integer> attempts = new AtomicReference<>(0);
        List<String> uploaded = new ArrayList<>();
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runIdValue, result, jmx, jtl, log) -> { finished.set(result); return Optional.of(run); },
                (plan, output) -> { Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    int attempt = attempts.getAndSet(attempts.get() + 1);
                    Files.writeString(jtl, "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL\n"
                            + "1,5,\"HTTP Request [retry-step]\",200,OK," + (attempt > 0 ? "true" : "false") + "," + (attempt > 0 ? "" : "failed") + ",http://test.local/retry");
                    return new ProcessResult(attempt > 0 ? 0 : 1);
                },
                (runIdValue, samples) -> uploaded.add(samples.get(0).stepId()),
                Files.createTempDirectory("f2-09-worker-retry-"), "5.6.3");

        worker.runOnce();

        assertEquals(2, attempts.get());
        assertEquals(List.of("retry-step", "retry-step#attempt-2"), uploaded);
        assertEquals(0, finished.get().exitCode());
    }

    @Test
    void executesEnabledSuiteMembersSequentiallyAndSkipsDisabledMember() throws Exception {
        UUID runId = UUID.randomUUID();
        RunRecord run = new RunRecord(runId, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("""
                {"planId":"suite-plan","targetType":"TEST_SUITE","suiteSteps":[
                  {"memberId":"m-2","position":2,"targetType":"API_CASE","targetId":"c-2","enabled":true,
                   "plan":{"planId":"p-2","stepId":"m-2","baseUrl":"http://127.0.0.1","method":"GET","urlTemplate":"/second","body":{"type":"NONE"}}},
                  {"memberId":"m-1","position":1,"targetType":"API_CASE","targetId":"c-1","enabled":false,
                   "plan":{"planId":"p-1","stepId":"m-1","baseUrl":"http://127.0.0.1","method":"GET","urlTemplate":"/disabled","body":{"type":"NONE"}}},
                  {"memberId":"m-0","position":0,"targetType":"API_CASE","targetId":"c-0","enabled":true,
                   "plan":{"planId":"p-0","stepId":"m-0","baseUrl":"http://127.0.0.1","method":"GET","urlTemplate":"/first","body":{"type":"NONE"}}}
                ]}
                 """));
        List<String> compiled = new ArrayList<>();
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runIdValue, result, jmx, jtl, log) -> { finished.set(result); return Optional.of(run); },
                (plan, output) -> { compiled.add(plan.urlTemplate()); Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    int current = calls.getAndIncrement();
                    Files.writeString(jtl, "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL\n"
                            + "1,5,HTTP,200,OK,true,,http://test.local/" + current);
                    return new ProcessResult(0);
                },
                (runIdValue, samples) -> { }, Files.createTempDirectory("f2-10-worker-suite-"), "5.6.3");

        worker.runOnce();

        assertEquals(List.of("/first", "/second"), compiled);
        assertEquals(2, calls.get());
        assertEquals(0, finished.get().exitCode());
    }

    @Test
    void prefixesScenarioMemberResultIdsBeforeUploadingEvidence() throws Exception {
        UUID runId = UUID.randomUUID();
        RunRecord run = new RunRecord(runId, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("""
                {"planId":"suite-plan","targetType":"TEST_SUITE","suiteSteps":[
                  {"memberId":"member-scenario","position":0,"targetType":"SCENARIO","targetId":"scenario-1","enabled":true,
                   "plan":{"scenarioSteps":[
                     {"stepId":"shared-step","kind":"WAIT","enabled":true,"waitMillis":0,"failureStrategy":"STOP","plan":{"stepId":"shared-step"}}
                   ]}}
                ]}
                """));
        List<String> uploaded = new ArrayList<>();
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runIdValue, result, jmx, jtl, log) -> { finished.set(result); return Optional.of(run); },
                (plan, output) -> { Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> new ProcessResult(0),
                (runIdValue, samples) -> uploaded.add(samples.get(0).stepId()),
                Files.createTempDirectory("f2-10-worker-scenario-suite-"), "5.6.3");

        worker.runOnce();

        assertEquals(List.of("member-scenario/shared-step"), uploaded);
        assertEquals(0, finished.get().exitCode());
    }

    @Test
    void doesNotLetEarlierMemberExtractionOverrideLaterMemberVariable() throws Exception {
        UUID runId = UUID.randomUUID();
        String extracted = java.util.Base64.getEncoder().encodeToString(
                "[{\"variable\":\"memberValue\",\"type\":\"JSON_PATH\",\"matched\":true,\"value\":\"A\"}]"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RunRecord run = new RunRecord(runId, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("""
                {"planId":"suite-plan","targetType":"TEST_SUITE","suiteSteps":[
                  {"memberId":"member-a","position":0,"targetType":"API_CASE","targetId":"case-a","enabled":true,
                    "plan":{"planId":"case-a-plan","baseUrl":"http://127.0.0.1","method":"GET","urlTemplate":"/first","body":{"type":"NONE"},
                           "extractors":[{"type":"JSON_PATH","expression":"$.memberValue","variable":"memberValue"}]}},
                  {"memberId":"member-b","position":1,"targetType":"API_CASE","targetId":"case-b","enabled":true,
                     "plan":{"planId":"case-b-plan","baseUrl":"http://127.0.0.1","method":"GET","urlTemplate":"/second/${memberValue}",
                            "variableScopes":{"caseVariables":{"memberValue":"B"}},"body":{"type":"NONE"}}}
                 ]}
                 """));
        List<String> compiled = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ProcessResult> finished = new AtomicReference<>();
        RunnerWorker worker = new RunnerWorker(
                ignored -> Optional.of(run), ignored -> false,
                (runIdValue, result, jmx, jtl, log) -> { finished.set(result); return Optional.of(run); },
                (plan, output) -> { compiled.add(plan.urlTemplate()); Files.writeString(output, "jmx"); return output; },
                (work, jmx, jtl, log, certificate, cancel) -> {
                    int attempt = calls.getAndIncrement();
                    String line = attempt == 0
                            ? "1,5,HTTP,200,OK,true,,http://test.local/first,X-Autotest-Extractions: " + extracted
                            : "1,5,HTTP,200,OK,true,,http://test.local/second/B";
                    Files.writeString(jtl, "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL,responseHeaders\n" + line);
                    return new ProcessResult(0);
                },
                (runIdValue, samples) -> { }, Files.createTempDirectory("f2-02-worker-suite-scope-"), "5.6.3");

        worker.runOnce();

        assertEquals(List.of("/first", "/second/B"), compiled);
        assertEquals(2, calls.get());
        assertEquals(0, finished.get().exitCode());
    }

    private RunRecord run(UUID id, String method) throws Exception {
        return new RunRecord(id, "PENDING", "5.6.3", null, null, false, null, null, null, null,
                json.readTree("{\"planId\":\"plan-1\",\"stepId\":\"step-1\","
                        + "\"baseUrl\":\"http://127.0.0.1\",\"method\":\"" + method + "\","
                        + "\"urlTemplate\":\"/health\",\"body\":{\"type\":\"NONE\"}}"));
    }
}
