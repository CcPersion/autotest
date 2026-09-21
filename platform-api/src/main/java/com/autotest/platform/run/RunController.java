package com.autotest.platform.run;

import com.autotest.platform.audit.AuditService;
import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.security.ApiDomainException;
import com.autotest.platform.security.TraceIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
public class RunController {

    private final RunService service;
    private final UserRepository users;
    private final AuditService audit;
    private final PlanBuilder plans;

    public RunController(RunService service, UserRepository users, AuditService audit, PlanBuilder plans) {
        this.service = service;
        this.users = users;
        this.audit = audit;
        this.plans = plans;
    }

    @PostMapping
    public ResponseEntity<RunResponse> create(@PathVariable UUID projectId, @RequestBody RunRequest request,
                                              Authentication authentication, HttpServletRequest httpRequest) {
        UUID actorId = actor(authentication);
        if (request == null) throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "TARGET_INVALID", "运行目标不能为空");
        String targetType = request.targetType();
        JsonNode executionPlan = request.executionPlan();
        if (executionPlan == null && ("SAVED_EXECUTABLE".equalsIgnoreCase(targetType)
                || "API_CASE".equalsIgnoreCase(targetType))) {
            executionPlan = plans.buildSavedCase(projectId, request.targetId(), request.environmentId());
            targetType = "API_CASE";
        } else if (executionPlan == null) {
            throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "TARGET_INVALID", "运行只接受已保存可执行目标");
        } else if ("SAVED_EXECUTABLE".equalsIgnoreCase(targetType)) {
            throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "PLAN_TAMPERED", "PLAN_TAMPERED");
        }
        RunService.CreateResult result = service.create(projectId, request.environmentId(), targetType,
                request.targetId(), executionPlan, request.idempotencyKey(), actorId);
        if (result.created()) {
            audit.record(actorId, projectId, "RUN_CREATED", "RUN", result.run().id(), TraceIdFilter.traceId(httpRequest),
                    result.run().id(), null, JsonNodeFactory.instance.objectNode().put("targetType", result.run().targetType()));
        }
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(RunResponse.from(result.run()));
    }

    @GetMapping("/{runId}")
    public RunResponse get(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return RunResponse.from(service.get(projectId, runId));
    }

    @GetMapping
    public List<RunResponse> list(@PathVariable UUID projectId,
                                  @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) {
        return service.list(projectId, limit).stream().map(RunResponse::from).toList();
    }

    @PostMapping("/{runId}/cancel")
    public RunResponse cancel(@PathVariable UUID projectId, @PathVariable UUID runId,
                              Authentication authentication, HttpServletRequest httpRequest) {
        UUID actorId = actor(authentication);
        RunRecord run = service.cancel(projectId, runId);
        audit.record(actorId, projectId, "RUN_CANCEL_REQUESTED", "RUN", run.id(), TraceIdFilter.traceId(httpRequest),
                run.id(), null, JsonNodeFactory.instance.objectNode());
        return RunResponse.from(run);
    }

    private UUID actor(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        }
        return user.id();
    }

    public record RunRequest(UUID environmentId, String targetType, UUID targetId,
                             JsonNode executionPlan, String idempotencyKey) {
        @JsonAnySetter
        public void rejectUnknownField(String name, JsonNode value) {
            throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "PLAN_TAMPERED", "PLAN_TAMPERED");
        }
    }

    public record RunResponse(UUID id, UUID projectId, UUID environmentId, String targetType, UUID targetId,
                              String status, JsonNode executionPlan, String idempotencyKey, String jmeterVersion,
                              Instant startedAt, Instant finishedAt, Integer exitCode, String jmxPath, String jtlPath,
                              String logPath, boolean cancelRequested, Instant createdAt) {
        public static RunResponse from(RunRecord run) {
            return new RunResponse(run.id(), run.projectId(), run.environmentId(), run.targetType(), run.targetId(),
                    run.status(), run.executionPlan(), run.idempotencyKey(), run.jmeterVersion(), run.startedAt(),
                    run.finishedAt(), run.exitCode(), run.jmxPath(), run.jtlPath(), run.logPath(),
                    run.cancelRequested(), run.createdAt());
        }
    }
}
