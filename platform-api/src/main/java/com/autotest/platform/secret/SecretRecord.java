package com.autotest.platform.secret;

import java.time.Instant;
import java.util.UUID;

public record SecretRecord(UUID id, UUID projectId, String name, int revision,
                           boolean archived, Instant createdAt, Instant updatedAt) {
}
