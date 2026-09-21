package com.autotest.platform.file;

import java.time.Instant;
import java.util.UUID;

public record FileAssetRecord(UUID id, UUID projectId, String kind, String originalName,
                       String mimeType, long size, String sha256, String status,
                       String objectKey, int revision, Instant createdAt, Instant updatedAt) {
}
