package com.autotest.platform.schedule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WebhookRecord(UUID id, UUID projectId, String name, String url, String secretRef,
                            List<String> events, boolean enabled, int revision, Instant createdAt, Instant updatedAt) {
}
