package com.autotest.platform.importer;

import java.util.List;

public record ImportDocument(String sourceType, List<ImportCandidate> candidates, List<String> warnings) {
    public ImportDocument {
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
