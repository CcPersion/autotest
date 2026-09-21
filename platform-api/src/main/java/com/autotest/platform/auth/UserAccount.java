package com.autotest.platform.auth;

import java.util.UUID;

public record UserAccount(UUID id, String username, String passwordHash, int revision) {
}
