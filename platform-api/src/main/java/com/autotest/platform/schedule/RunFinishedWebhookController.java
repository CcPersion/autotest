package com.autotest.platform.schedule;

import com.autotest.platform.run.RunRecord;
import com.autotest.platform.run.RunRepository;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

/** Runner 完成回调；平台从数据库读取真实终态，不信任 Runner 请求中的状态字段。 */
@RestController
public class RunFinishedWebhookController {
    private final RunRepository runs;
    private final WebhookService webhooks;
    private final String callbackToken;
    private final ObjectMapper json = new ObjectMapper();

    public RunFinishedWebhookController(RunRepository runs, WebhookService webhooks,
                                        @Value("${autotest.runner.callback-token:}") String callbackToken) {
        this.runs = runs;
        this.webhooks = webhooks;
        this.callbackToken = callbackToken == null ? "" : callbackToken;
    }

    @PostMapping("/api/v1/internal/runs/{runId}/finished")
    public ResponseEntity<Void> finished(@PathVariable UUID runId,
                                         @RequestHeader(value = "X-Runner-Token", required = false) String suppliedToken,
                                         @RequestBody(required = false) FinishedRequest ignoredRequest) {
        verifyToken(suppliedToken);
        RunRecord run = runs.findById(runId);
        if (run == null) throw new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
        ObjectNode summary = json.createObjectNode();
        summary.put("runId", run.id().toString());
        summary.put("projectId", run.projectId().toString());
        summary.put("status", run.status());
        summary.put("targetType", run.targetType());
        if (run.exitCode() != null) summary.put("exitCode", run.exitCode());
        putInstant(summary, "startedAt", run.startedAt());
        putInstant(summary, "finishedAt", run.finishedAt());
        webhooks.notify(run.projectId(), "RUN_FINISHED", summary);
        return ResponseEntity.noContent().build();
    }

    private void verifyToken(String supplied) {
        if (callbackToken.isBlank() || supplied == null
                || !MessageDigest.isEqual(callbackToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "RUNNER_AUTHENTICATION_REQUIRED", "Runner 认证失败");
        }
    }

    private static void putInstant(ObjectNode target, String name, Instant value) {
        if (value != null) target.put(name, value.toString());
    }

    public record FinishedRequest(String status, Integer exitCode, Instant startedAt, Instant finishedAt) {
    }
}
