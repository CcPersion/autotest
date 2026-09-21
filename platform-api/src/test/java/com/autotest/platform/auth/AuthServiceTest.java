package com.autotest.platform.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    @Test
    void unknownUserStillPerformsOneDummyBcryptMatch() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.findByUsername("missing@example.com")).thenReturn(null);
        AuthService service = new AuthService(users, encoder);

        assertNull(service.authenticate("missing@example.com", "not-recorded"));

        verify(encoder).matches("not-recorded", AuthService.DUMMY_PASSWORD_HASH);
        verifyNoMoreInteractions(encoder);
    }

    @Test
    void wrongPasswordPerformsOneMatchAgainstStoredHash() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        UserAccount account = new UserAccount(UUID.randomUUID(), "user@example.com", "$2a$10$stored", 0);
        when(users.findByUsername("user@example.com")).thenReturn(account);
        when(encoder.matches(anyString(), anyString())).thenReturn(false);
        AuthService service = new AuthService(users, encoder);

        assertNull(service.authenticate("user@example.com", "wrong"));

        verify(encoder).matches("wrong", account.passwordHash());
        verifyNoMoreInteractions(encoder);
    }
}
