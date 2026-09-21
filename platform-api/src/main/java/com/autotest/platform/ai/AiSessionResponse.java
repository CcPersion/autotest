package com.autotest.platform.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AiSessionResponse(UUID id, UUID projectId, UUID modelConfigId, String title,
                                Instant createdAt, Instant updatedAt, List<AiMessageRecord> messages) {
    public static AiSessionResponse from(AiSessionRecord record, List<AiMessageRecord> messages) {
        return new AiSessionResponse(record.id(), record.projectId(), record.modelConfigId(), record.title(),
                record.createdAt(), record.updatedAt(), messages);
    }
}
