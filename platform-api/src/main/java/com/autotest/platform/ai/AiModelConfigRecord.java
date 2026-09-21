package com.autotest.platform.ai;

import java.time.Instant;
import java.util.UUID;

public record AiModelConfigRecord(UUID id, String name, String baseUrl, String modelName,
                                  String apiKeySecretRef, boolean enabled, int revision,
                                  Instant createdAt, Instant updatedAt, String providerType) {
}
