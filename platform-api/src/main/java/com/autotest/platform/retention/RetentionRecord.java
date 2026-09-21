package com.autotest.platform.retention;

import java.time.Instant;
import java.util.UUID;

public record RetentionRecord(UUID projectId, int retentionDays, int revision, Instant updatedAt) {
}
