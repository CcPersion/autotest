package com.autotest.platform.ai;

import java.time.Instant;
import java.util.UUID;

public record AiModelConfigResponse(UUID id, String name, String baseUrl, String modelName,
                                    String apiKeySecretRef, boolean enabled, int revision,
                                    Instant createdAt, Instant updatedAt, String providerType) {
    public static AiModelConfigResponse from(AiModelConfigRecord record) {
        return new AiModelConfigResponse(record.id(), record.name(), record.baseUrl(), record.modelName(),
                record.apiKeySecretRef(), record.enabled(), record.revision(), record.createdAt(), record.updatedAt(),
                record.providerType());
    }
}
