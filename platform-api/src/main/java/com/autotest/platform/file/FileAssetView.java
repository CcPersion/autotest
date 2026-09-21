package com.autotest.platform.file;

import java.time.Instant;
import java.util.UUID;

public record FileAssetView(UUID fileId, UUID projectId, String kind, String originalName,
                            String mimeType, long size, String sha256, String status,
                            int revision, Instant createdAt, Instant updatedAt) {
    static FileAssetView from(FileAssetRecord asset) {
        return new FileAssetView(asset.id(), asset.projectId(), asset.kind(), asset.originalName(),
                asset.mimeType(), asset.size(), asset.sha256(), asset.status(), asset.revision(),
                asset.createdAt(), asset.updatedAt());
    }
}
