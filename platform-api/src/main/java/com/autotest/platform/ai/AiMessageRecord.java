package com.autotest.platform.ai;

import java.time.Instant;
import java.util.UUID;

public record AiMessageRecord(UUID id, UUID sessionId, String role, String eventType,
                              String content, String toolName, Instant createdAt) {
}
