package com.autotest.platform.importer;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

public record ImportPreviewItem(
        int index,
        String sourcePath,
        String definitionName,
        String caseName,
        String method,
        String urlTemplate,
        JsonNode requestSpec,
        JsonNode caseSpec,
        JsonNode variables,
        JsonNode assertions,
        String conflictType,
        UUID existingDefinitionId,
        UUID existingCaseId,
        ImportAction recommendedAction,
        List<String> warnings,
        List<String> errors) {
    public ImportPreviewItem {
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        errors = List.copyOf(errors == null ? List.of() : errors);
    }
}
