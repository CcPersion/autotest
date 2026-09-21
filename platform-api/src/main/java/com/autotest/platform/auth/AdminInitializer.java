package com.autotest.platform.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 只在空 users 表首次启动时创建部署管理员。 */
@Component
public class AdminInitializer implements ApplicationRunner {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String configuredUsername;
    private final String configuredPassword;

    public AdminInitializer(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            @Value("${autotest.admin.username:}") String configuredUsername,
            @Value("${autotest.admin.password:}") String configuredPassword) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.configuredUsername = configuredUsername;
        this.configuredPassword = configuredPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        if (UsernameNormalizer.normalize(configuredUsername).isEmpty()
                || configuredPassword == null || configuredPassword.isBlank()) {
            throw new IllegalStateException(
                    "AUTOTEST_ADMIN_USERNAME and AUTOTEST_ADMIN_PASSWORD are required for an empty users table");
        }
        users.insert(configuredUsername, passwordEncoder.encode(configuredPassword));
    }
}
