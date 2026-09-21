package com.autotest.platform.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AiPatchPreviewResponse(UUID previewId, UUID projectId, String targetType, String title,
                                     UUID targetId, UUID parentId, Integer baseRevision, int currentRevision,
                                     List<AiPatchChange> changes, List<String> warnings,
                                     List<AiPatchFieldError> errors, boolean canConfirm, Instant expiresAt) {
}
