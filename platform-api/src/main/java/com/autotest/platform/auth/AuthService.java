package com.autotest.platform.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    public static final String INVALID_CREDENTIALS_MESSAGE = "用户名或密码错误";
    static final String DUMMY_PASSWORD_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    public UserAccount authenticate(String username, String password) {
        UserAccount account = users.findByUsername(username);
        String candidate = password == null ? "" : password;
        if (account == null) {
            passwordEncoder.matches(candidate, DUMMY_PASSWORD_HASH);
            return null;
        }
        if (!passwordEncoder.matches(candidate, account.passwordHash())) {
            return null;
        }
        return account;
    }

    @Transactional
    public UserAccount changePassword(String username, String currentPassword, String newPassword) {
        UserAccount account = users.findByUsername(username);
        if (account == null || currentPassword == null
                || !passwordEncoder.matches(currentPassword, account.passwordHash())) {
            return null;
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("新密码不能为空");
        }
        int updated = users.updatePassword(account.id(), passwordEncoder.encode(newPassword));
        if (updated != 1) {
            throw new IllegalStateException("用户密码更新失败");
        }
        return new UserAccount(account.id(), account.username(), "", account.revision() + 1);
    }
}
