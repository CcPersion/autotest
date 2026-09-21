package com.autotest.platform.scenario;

import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.security.ApiDomainException;
import com.autotest.platform.run.RunController;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/scenarios")
public class ScenarioController {
    private final ScenarioService service;
    private final ScenarioRunService runs;
    private final UserRepository users;

    public ScenarioController(ScenarioService service, ScenarioRunService runs, UserRepository users) {
        this.service = service;
        this.runs = runs;
        this.users = users;
    }

    @GetMapping
    public List<ScenarioResponse> list(@PathVariable UUID projectId,
                                       @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(projectId, includeArchived).stream().map(ScenarioResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<ScenarioResponse> create(@PathVariable UUID projectId, @RequestBody ScenarioWrite request,
                                                   Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ScenarioResponse.from(
                service.create(projectId, request, actor(authentication))));
    }

    @GetMapping("/{scenarioId}")
    public ScenarioResponse get(@PathVariable UUID projectId, @PathVariable UUID scenarioId) {
        return ScenarioResponse.from(service.get(projectId, scenarioId));
    }

    @PutMapping("/{scenarioId}")
    public ScenarioResponse update(@PathVariable UUID projectId, @PathVariable UUID scenarioId,
                                   @RequestBody ScenarioWrite request, Authentication authentication) {
        return ScenarioResponse.from(service.update(projectId, scenarioId, request, actor(authentication)));
    }

    @PostMapping("/{scenarioId}/archive")
    public ScenarioResponse archive(@PathVariable UUID projectId, @PathVariable UUID scenarioId,
                                    @RequestBody RevisionRequest request, Authentication authentication) {
        return ScenarioResponse.from(service.archive(projectId, scenarioId,
                request == null ? null : request.revision(), actor(authentication)));
    }

    @PostMapping("/{scenarioId}/runs")
    public RunController.RunResponse run(@PathVariable UUID projectId, @PathVariable UUID scenarioId,
                                         @RequestBody RunRequest request, Authentication authentication) {
        return RunController.RunResponse.from(runs.create(projectId, scenarioId,
                request == null ? null : request.environmentId(),
                request == null ? null : request.idempotencyKey(), actor(authentication)));
    }

    private UUID actor(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        return user.id();
    }

    public record RevisionRequest(Integer revision) {
        @JsonAnySetter
        public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
            throw new IllegalArgumentException("请求格式不正确");
        }
    }

    public record RunRequest(UUID environmentId, String idempotencyKey) {
        @JsonAnySetter
        public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
            throw new IllegalArgumentException("请求格式不正确");
        }
    }
}
