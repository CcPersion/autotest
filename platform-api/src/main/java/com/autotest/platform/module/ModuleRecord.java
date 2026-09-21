package com.autotest.platform.module;

import java.time.Instant;
import java.util.UUID;

public record ModuleRecord(UUID id, UUID projectId, UUID parentId, String name,
                           int sortOrder, int revision, boolean archived,
                           Instant createdAt, Instant updatedAt) {
}
