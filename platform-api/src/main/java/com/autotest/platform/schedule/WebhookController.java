package com.autotest.platform.schedule;

import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/webhooks")
public class WebhookController {
    private final WebhookService service;
    private final UserRepository users;

    public WebhookController(WebhookService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public List<WebhookResponse> list(@PathVariable UUID projectId) {
        return service.list(projectId).stream().map(WebhookResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<WebhookResponse> create(@PathVariable UUID projectId, @RequestBody WebhookWrite request,
                                                   Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(WebhookResponse.from(
                service.create(projectId, request, actor(authentication))));
    }

    @PutMapping("/{webhookId}")
    public WebhookResponse update(@PathVariable UUID projectId, @PathVariable UUID webhookId,
                                  @RequestBody WebhookWrite request, Authentication authentication) {
        return WebhookResponse.from(service.update(projectId, webhookId, request, actor(authentication)));
    }

    private UUID actor(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        return user.id();
    }

    public record WebhookResponse(UUID id, UUID projectId, String name, String url, String secretRef,
                                  List<String> events, boolean enabled, int revision, Instant createdAt, Instant updatedAt) {
        static WebhookResponse from(WebhookRecord record) {
            return new WebhookResponse(record.id(), record.projectId(), record.name(), record.url(), record.secretRef(),
                    record.events(), record.enabled(), record.revision(), record.createdAt(), record.updatedAt());
        }
    }
}
