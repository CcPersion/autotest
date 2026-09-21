package com.autotest.platform.importer;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record ImportCandidate(
        String sourcePath,
        String definitionName,
        String caseName,
        String method,
        String urlTemplate,
        JsonNode requestSpec,
        JsonNode caseSpec,
        JsonNode variables,
        JsonNode assertions,
        List<String> warnings) {

    public ImportCandidate {
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
