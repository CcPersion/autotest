package com.autotest.platform.project;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectRecord(
        UUID id,
        String name,
        String description,
        int revision,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        List<String> targetAllowlist) {

    public ProjectRecord(UUID id, String name, String description, int revision, boolean archived,
                         Instant createdAt, Instant updatedAt) {
        this(id, name, description, revision, archived, createdAt, updatedAt, List.of());
    }

    public ProjectRecord {
        targetAllowlist = targetAllowlist == null ? List.of() : List.copyOf(targetAllowlist);
    }
}
