package com.autotest.platform.environment;

import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.security.ApiDomainException;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/environments")
public class EnvironmentController {

    private final EnvironmentService service;
    private final UserRepository users;

    public EnvironmentController(EnvironmentService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public List<EnvironmentResponse> list(@PathVariable UUID projectId,
                                          @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(projectId, includeArchived).stream().map(EnvironmentResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<EnvironmentResponse> create(@PathVariable UUID projectId,
                                                      @RequestBody EnvironmentWrite request,
                                                      Authentication authentication) {
        EnvironmentRecord environment = service.create(projectId, request == null ? null : request.name(),
                request == null ? null : request.baseUrl(), request == null ? null : request.variables(),
                request == null ? null : request.requestOptions(), actor(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(EnvironmentResponse.from(environment));
    }

    @GetMapping("/{environmentId}")
    public EnvironmentResponse get(@PathVariable UUID projectId, @PathVariable UUID environmentId) {
        return EnvironmentResponse.from(service.get(projectId, environmentId));
    }

    @PutMapping("/{environmentId}")
    public EnvironmentResponse update(@PathVariable UUID projectId, @PathVariable UUID environmentId,
                                      @RequestBody EnvironmentWrite request, Authentication authentication) {
        EnvironmentRecord environment = service.update(projectId, environmentId,
                request == null ? null : request.name(), request == null ? null : request.baseUrl(),
                request == null ? null : request.variables(), request == null ? null : request.requestOptions(),
                request == null ? null : request.revision(), actor(authentication));
        return EnvironmentResponse.from(environment);
    }

    @PostMapping("/{environmentId}/archive")
    public EnvironmentResponse archive(@PathVariable UUID projectId, @PathVariable UUID environmentId,
                                       @RequestBody RevisionRequest request, Authentication authentication) {
        return EnvironmentResponse.from(service.archive(projectId, environmentId,
                request == null ? null : request.revision(), actor(authentication)));
    }

    @PostMapping("/{environmentId}/restore")
    public EnvironmentResponse restore(@PathVariable UUID projectId, @PathVariable UUID environmentId,
                                       @RequestBody RevisionRequest request, Authentication authentication) {
        return EnvironmentResponse.from(service.restore(projectId, environmentId,
                request == null ? null : request.revision(), actor(authentication)));
    }

    private UUID actor(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        }
        return user.id();
    }

    public record EnvironmentWrite(String name, String baseUrl, JsonNode variables, JsonNode requestOptions, Integer revision) {
    }

    public record RevisionRequest(Integer revision) {
    }

    public record EnvironmentResponse(UUID id, UUID projectId, String name, String baseUrl, JsonNode variables,
                                      JsonNode requestOptions,
                                      int revision, boolean archived, Instant createdAt, Instant updatedAt) {
        private static EnvironmentResponse from(EnvironmentRecord environment) {
            return new EnvironmentResponse(environment.id(), environment.projectId(), environment.name(),
                    environment.baseUrl(), environment.variables(), environment.requestOptions(), environment.revision(), environment.archived(),
                    environment.createdAt(), environment.updatedAt());
        }
    }
}
