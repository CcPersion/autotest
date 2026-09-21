package com.autotest.platform.importer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ImportPreviewResponse(UUID previewId, UUID projectId, String sourceType,
                                    int projectRevision, List<ImportPreviewItem> items,
                                    List<String> warnings, Instant expiresAt) {
    public ImportPreviewResponse {
        items = List.copyOf(items == null ? List.of() : items);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
