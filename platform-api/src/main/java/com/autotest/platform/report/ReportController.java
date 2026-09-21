package com.autotest.platform.report;

import com.autotest.platform.run.RunRecord;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class ReportController {

    private final ReportService service;
    private final String callbackToken;

    public ReportController(ReportService service,
                             @Value("${autotest.runner.callback-token:}") String callbackToken) {
        this.service = service;
        this.callbackToken = callbackToken == null ? "" : callbackToken;
    }

    @GetMapping("/api/v1/projects/{projectId}/runs/{runId}/report")
    public ReportResponse get(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return ReportResponse.from(service.get(projectId, runId));
    }

    @PostMapping("/api/v1/internal/runs/{runId}/step-results")
    public ResponseEntity<StepResultResponse> receive(@PathVariable UUID runId,
                                                       @RequestHeader(value = "X-Runner-Token", required = false) String token,
                                                       @RequestBody StepResultWrite request) {
        verifyToken(token);
        return ResponseEntity.ok(StepResultResponse.from(service.receive(runId, request), null));
    }

    private void verifyToken(String supplied) {
        if (callbackToken.isBlank() || supplied == null
                || !MessageDigest.isEqual(callbackToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "RUNNER_AUTHENTICATION_REQUIRED", "Runner 认证失败");
        }
    }

    public record ReportResponse(UUID runId, String status, Instant startedAt, Instant finishedAt,
                                 Integer exitCode, String cleanupStatus, List<SuiteMemberReportResolver.Member> suiteMembers,
                                 List<StepResultResponse> steps) {
        static ReportResponse from(RunReport report) {
            RunRecord run = report.run();
            List<SuiteMemberReportResolver.Member> members = SuiteMemberReportResolver.resolve(run.executionPlan());
            return new ReportResponse(run.id(), run.status(), run.startedAt(), run.finishedAt(), run.exitCode(),
                    CleanupStatusResolver.resolve(run, report.steps()), members,
                    report.steps().stream().map(step -> StepResultResponse.from(step,
                            SuiteMemberReportResolver.memberIdForResultKey(step.resultKey(), members))).toList());
        }
    }

    public record StepResultResponse(UUID id, UUID runId, UUID stepId, String resultKey, String memberId, int sequenceNo,
                                     String status, long durationMs, JsonNode requestSummary,
                                     JsonNode responseSummary, JsonNode assertions, JsonNode extractions, JsonNode errorSummary,
                                     Instant startedAt, Instant finishedAt, Instant createdAt) {
        static StepResultResponse from(StepResultRecord result, String memberId) {
            return new StepResultResponse(result.id(), result.runId(), result.stepId(), result.resultKey(), memberId,
                    result.sequenceNo(), result.status(), result.durationMs(), result.requestSummary(),
                    result.responseSummary(), result.assertions(), result.extractions(), result.errorSummary(), result.startedAt(),
                    result.finishedAt(), result.createdAt());
        }
    }
}
