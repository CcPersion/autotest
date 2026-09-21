package com.autotest.platform.auth;

import com.autotest.platform.security.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    @Test
    void lockedLoginStillRunsAuthenticationCheckBeforeReturningGenericError() {
        AuthService authService = mock(AuthService.class);
        UserRepository users = mock(UserRepository.class);
        SecurityContextRepository securityContext = mock(SecurityContextRepository.class);
        CsrfTokenRepository csrfTokens = mock(CsrfTokenRepository.class);
        LoginRateLimiter limiter = new LoginRateLimiter(Clock.fixed(
                Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("127.0.0.1", "admin@example.com");
        }
        UserAccount account = new UserAccount(UUID.randomUUID(), "admin@example.com", "$2a$10$stored", 0);
        when(authService.authenticate("admin@example.com", "correct")).thenReturn(account);
        AuthController controller = new AuthController(authService, users, limiter, securityContext, csrfTokens);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<?> result = controller.login(
                new AuthController.LoginRequest("admin@example.com", "correct"), request, response);

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, result.getStatusCode().value());
        assertEquals("AUTH_INVALID_CREDENTIALS", ((ApiError) result.getBody()).code());
        verify(authService).authenticate("admin@example.com", "correct");
    }
}
