package com.autotest.platform.auth;

import com.autotest.platform.audit.AuditService;
import com.autotest.platform.security.ApiError;
import com.autotest.platform.security.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String INVALID_CREDENTIALS = AuthService.INVALID_CREDENTIALS_MESSAGE;

    private final AuthService authService;
    private final UserRepository users;
    private final LoginRateLimiter rateLimiter;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final AuditService audit;

    @Autowired
    public AuthController(AuthService authService,
                          UserRepository users,
                          LoginRateLimiter rateLimiter,
                          SecurityContextRepository securityContextRepository,
                          CsrfTokenRepository csrfTokenRepository,
                          AuditService audit) {
        this.authService = authService;
        this.users = users;
        this.rateLimiter = rateLimiter;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.audit = audit;
    }

    /** 保留给无 Spring 的认证控制器单元测试；生产构造器始终注入审计服务。 */
    public AuthController(AuthService authService,
                          UserRepository users,
                          LoginRateLimiter rateLimiter,
                          SecurityContextRepository securityContextRepository,
                          CsrfTokenRepository csrfTokenRepository) {
        this(authService, users, rateLimiter, securityContextRepository, csrfTokenRepository, null);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest,
                                   HttpServletResponse httpResponse) {
        String username = UsernameNormalizer.normalize(request == null ? null : request.username());
        String remoteAddress = httpRequest.getRemoteAddr();
        UserAccount account = authService.authenticate(username, request == null ? null : request.password());
        if (rateLimiter.isLocked(remoteAddress, username)) {
            return invalidCredentials(httpRequest);
        }

        if (account == null) {
            rateLimiter.recordFailure(remoteAddress, username);
            return invalidCredentials(httpRequest);
        }
        rateLimiter.clear(remoteAddress, username);

        // 显式创建并迁移 Session，避免认证前后复用旧 Session ID。
        httpRequest.getSession(true);
        httpRequest.changeSessionId();
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                account.username(), null, AuthorityUtils.NO_AUTHORITIES);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
        CsrfToken token = csrfTokenRepository.generateToken(httpRequest);
        csrfTokenRepository.saveToken(token, httpRequest, httpResponse);
        if (audit != null) {
            audit.record(account.id(), null, "LOGIN_SUCCEEDED", "USER", account.id(),
                    TraceIdFilter.traceId(httpRequest), null, null, JsonNodeFactory.instance.objectNode());
        }

        return ResponseEntity.ok(new UserResponse(account));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        securityContextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication, HttpServletRequest request) {
        UserAccount account = users.findByUsername(authentication.getName());
        if (account == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error(request, "AUTHENTICATION_REQUIRED", "请先登录"));
        }
        return ResponseEntity.ok(new UserResponse(account));
    }

    @PutMapping("/password")
    public ResponseEntity<?> changePassword(@RequestBody PasswordChangeRequest request,
                                            Authentication authentication,
                                            HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) {
        UserAccount account = authService.changePassword(
                authentication.getName(),
                request == null ? null : request.currentPassword(),
                request == null ? null : request.newPassword());
        if (account == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(error(httpRequest, "PASSWORD_INVALID", "当前密码不正确"));
        }

        SecurityContextHolder.clearContext();
        var session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        securityContextRepository.saveContext(SecurityContextHolder.createEmptyContext(), httpRequest, httpResponse);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<ApiError> invalidCredentials(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(error(request, "AUTH_INVALID_CREDENTIALS", INVALID_CREDENTIALS));
    }

    private ApiError error(HttpServletRequest request, String code, String message) {
        return new ApiError(code, message, null,
                request == null ? "unknown" : TraceIdFilter.traceId(request));
    }

    public record LoginRequest(String username, String password) {
    }

    public record PasswordChangeRequest(String currentPassword, String newPassword) {
    }

    public record UserResponse(String id, String username, int revision) {
        private UserResponse(UserAccount account) {
            this(account.id().toString(), account.username(), account.revision());
        }
    }
}
