package com.autotest.platform.ai;

import java.time.Instant;
import java.util.UUID;

public record AiSessionRecord(UUID id, UUID projectId, UUID modelConfigId, String title,
                              UUID createdBy, Instant createdAt, Instant updatedAt) {
}
