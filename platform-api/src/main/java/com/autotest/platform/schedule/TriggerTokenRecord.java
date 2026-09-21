package com.autotest.platform.schedule;

import java.time.Instant;
import java.util.UUID;

public record TriggerTokenRecord(UUID id, UUID projectId, String name, String tokenHash, boolean active,
                                 UUID createdBy, Instant createdAt, Instant lastUsedAt) {
}
