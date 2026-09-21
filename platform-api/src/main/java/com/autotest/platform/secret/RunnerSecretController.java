package com.autotest.platform.secret;

import com.autotest.platform.security.ApiDomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Runner 运行时密钥解析入口；不暴露给浏览器会话。 */
@RestController
public class RunnerSecretController {

    private final SecretService secrets;
    private final String callbackToken;

    public RunnerSecretController(SecretService secrets,
                                  @Value("${autotest.runner.callback-token:}") String callbackToken) {
        this.secrets = secrets;
        this.callbackToken = callbackToken == null ? "" : callbackToken;
    }

    @GetMapping("/api/v1/internal/projects/{projectId}/secrets/{name}/value")
    public ResponseEntity<SecretValueResponse> resolve(@PathVariable UUID projectId,
                                                        @PathVariable String name,
                                                        @RequestHeader(value = "X-Runner-Token", required = false)
                                                        String suppliedToken) {
        verifyToken(suppliedToken);
        return ResponseEntity.ok(new SecretValueResponse(secrets.resolveForRunner(projectId, name)));
    }

    private void verifyToken(String supplied) {
        if (callbackToken.isBlank() || supplied == null
                || !MessageDigest.isEqual(callbackToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "RUNNER_AUTHENTICATION_REQUIRED", "Runner 认证失败");
        }
    }

    public record SecretValueResponse(String value) {
    }
}
