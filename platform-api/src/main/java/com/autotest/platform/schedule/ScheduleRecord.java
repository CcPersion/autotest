package com.autotest.platform.schedule;

import java.time.Instant;
import java.util.UUID;

public record ScheduleRecord(UUID id, UUID projectId, UUID suiteId, UUID environmentId, String name,
                             String cronExpression, String zoneId, boolean enabled, int revision,
                             Instant nextRunAt, Instant lastRunAt, UUID createdBy, UUID updatedBy,
                             Instant createdAt, Instant updatedAt) {
}
