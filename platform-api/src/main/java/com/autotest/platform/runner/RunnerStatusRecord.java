package com.autotest.platform.runner;

import java.time.Instant;
import java.util.UUID;

public record RunnerStatusRecord(UUID runnerId, String runnerVersion, String jmeterVersion, UUID activeRunId,
                                 int queueDepth, Instant lastSeenAt, Instant updatedAt) {
}
