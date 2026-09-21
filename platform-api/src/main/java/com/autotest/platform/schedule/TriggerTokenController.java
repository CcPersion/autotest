package com.autotest.platform.schedule;

import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.security.ApiDomainException;
import com.autotest.platform.suite.TestSuiteRunService;
import com.autotest.platform.run.RunController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class TriggerTokenController {
    private final TriggerTokenService tokens;
    private final TestSuiteRunService suiteRuns;
    private final UserRepository users;

    public TriggerTokenController(TriggerTokenService tokens, TestSuiteRunService suiteRuns, UserRepository users) {
        this.tokens = tokens;
        this.suiteRuns = suiteRuns;
        this.users = users;
    }

    @GetMapping("/api/v1/projects/{projectId}/trigger-tokens")
    public List<TokenResponse> list(@PathVariable UUID projectId) {
        return tokens.list(projectId).stream().map(TokenResponse::from).toList();
    }

    @PostMapping("/api/v1/projects/{projectId}/trigger-tokens")
    public ResponseEntity<IssuedTokenResponse> create(@PathVariable UUID projectId, @RequestBody TokenWrite request,
                                                      Authentication authentication) {
        UserAccount actor = actor(authentication);
        TriggerTokenService.IssuedToken issued = tokens.create(projectId, request == null ? null : request.name(), actor.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(IssuedTokenResponse.from(issued));
    }

    @DeleteMapping("/api/v1/projects/{projectId}/trigger-tokens/{tokenId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID projectId, @PathVariable UUID tokenId) {
        tokens.revoke(projectId, tokenId);
        return ResponseEntity.noContent().build();
    }

    /** CI/API 触发入口：只凭项目级令牌，不创建浏览器会话。 */
    @PostMapping("/api/v1/trigger/projects/{projectId}/test-suites/{suiteId}/runs")
    public ResponseEntity<RunController.RunResponse> trigger(@PathVariable UUID projectId, @PathVariable UUID suiteId,
                                                              @RequestHeader(value = "X-Autotest-Trigger-Token", required = false)
                                                              String token, @RequestBody TriggerRequest request) {
        TriggerTokenRecord matched = tokens.authenticate(projectId, token);
        var result = suiteRuns.createResult(projectId, suiteId, request == null ? null : request.environmentId(),
                request == null ? null : request.idempotencyKey(), matched.createdBy());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(RunController.RunResponse.from(result.run()));
    }

    private UserAccount actor(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        return user;
    }

    public record TokenWrite(String name) {
    }

    public record TriggerRequest(UUID environmentId, String idempotencyKey) {
    }

    public record TokenResponse(UUID id, UUID projectId, String name, boolean active, Instant createdAt,
                                Instant lastUsedAt) {
        static TokenResponse from(TriggerTokenRecord record) {
            return new TokenResponse(record.id(), record.projectId(), record.name(), record.active(),
                    record.createdAt(), record.lastUsedAt());
        }
    }

    public record IssuedTokenResponse(TokenResponse token, String plaintext) {
        static IssuedTokenResponse from(TriggerTokenService.IssuedToken issued) {
            return new IssuedTokenResponse(TokenResponse.from(issued.token()), issued.plaintext());
        }
    }
}
