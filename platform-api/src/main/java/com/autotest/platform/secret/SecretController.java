package com.autotest.platform.secret;

import com.autotest.platform.audit.AuditService;
import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.security.ApiDomainException;
import com.autotest.platform.security.TraceIdFilter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.servlet.http.HttpServletRequest;
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
@RequestMapping("/api/v1/projects/{projectId}/secrets")
public class SecretController {

    private final SecretService service;
    private final UserRepository users;
    private final AuditService audit;

    public SecretController(SecretService service, UserRepository users, AuditService audit) {
        this.service = service;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping
    public List<SecretResponse> list(@PathVariable UUID projectId,
                                    @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(projectId, includeArchived).stream().map(SecretResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<SecretResponse> create(@PathVariable UUID projectId,
                                                 @RequestBody CreateSecretRequest request,
                                                 Authentication authentication, HttpServletRequest httpRequest) {
        SecretRecord secret = service.create(projectId, request == null ? null : request.name(),
                request == null ? null : request.value(), actor(authentication));
        audit.record(actor(authentication), projectId, "SECRET_CREATED", "SECRET", secret.id(),
                TraceIdFilter.traceId(httpRequest), null, null,
                JsonNodeFactory.instance.objectNode().put("name", secret.name()));
        return ResponseEntity.status(HttpStatus.CREATED).body(SecretResponse.from(secret));
    }

    @PutMapping("/{secretId}")
    public SecretResponse replace(@PathVariable UUID projectId, @PathVariable UUID secretId,
                                  @RequestBody ReplaceSecretRequest request, Authentication authentication,
                                  HttpServletRequest httpRequest) {
        SecretRecord secret = service.replace(projectId, secretId, request == null ? null : request.value(),
                request == null ? null : request.revision(), actor(authentication));
        audit.record(actor(authentication), projectId, "SECRET_REPLACED", "SECRET", secret.id(),
                TraceIdFilter.traceId(httpRequest), null, null, JsonNodeFactory.instance.objectNode().put("name", secret.name()));
        return SecretResponse.from(secret);
    }

    @PostMapping("/{secretId}/archive")
    public SecretResponse archive(@PathVariable UUID projectId, @PathVariable UUID secretId,
                                  @RequestBody RevisionRequest request, Authentication authentication,
                                  HttpServletRequest httpRequest) {
        SecretRecord secret = service.archive(projectId, secretId, request == null ? null : request.revision(),
                actor(authentication));
        audit.record(actor(authentication), projectId, "SECRET_ARCHIVED", "SECRET", secret.id(),
                TraceIdFilter.traceId(httpRequest), null, null, JsonNodeFactory.instance.objectNode().put("name", secret.name()));
        return SecretResponse.from(secret);
    }

    private UUID actor(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        }
        return user.id();
    }

    public record CreateSecretRequest(String name, String value) {
    }

    public record ReplaceSecretRequest(String value, Integer revision) {
    }

    public record RevisionRequest(Integer revision) {
    }

    public record SecretResponse(UUID id, UUID projectId, String name, String mask, int revision,
                                 boolean archived, Instant createdAt, Instant updatedAt) {
        private static SecretResponse from(SecretRecord secret) {
            return new SecretResponse(secret.id(), secret.projectId(), secret.name(), "••••••••",
                    secret.revision(), secret.archived(), secret.createdAt(), secret.updatedAt());
        }
    }
}
