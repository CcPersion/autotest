package com.autotest.platform.suite;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TestSuiteRecord(UUID id, UUID projectId, String name, String description,
                              UUID environmentId, int revision, boolean archived,
                              Instant createdAt, Instant updatedAt, List<TestSuiteMemberRecord> members) {
}
