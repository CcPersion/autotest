package com.autotest.platform.runner;

import com.autotest.platform.security.ApiDomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

@RestController
public class RunnerStatusController {
    private final RunnerStatusService service;
    private final String callbackToken;

    public RunnerStatusController(RunnerStatusService service,
                                  @Value("${autotest.runner.callback-token:}") String callbackToken) {
        this.service = service;
        this.callbackToken = callbackToken == null ? "" : callbackToken;
    }

    @PostMapping("/api/v1/internal/runners/{runnerId}/heartbeat")
    public ResponseEntity<Void> heartbeat(@PathVariable UUID runnerId,
                                          @RequestHeader(value = "X-Runner-Token", required = false) String suppliedToken,
                                          @RequestBody RunnerStatusWrite request) {
        verifyToken(suppliedToken);
        if (request == null) throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", "Runner 心跳不能为空");
        service.heartbeat(runnerId, request.runnerVersion(), request.jmeterVersion(), request.activeRunId(), request.queueDepth());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/runners/status")
    public List<RunnerStatusService.Status> status() {
        return service.list();
    }

    private void verifyToken(String supplied) {
        if (callbackToken.isBlank() || supplied == null
                || !MessageDigest.isEqual(callbackToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "RUNNER_AUTHENTICATION_REQUIRED", "Runner 认证失败");
        }
    }
}
