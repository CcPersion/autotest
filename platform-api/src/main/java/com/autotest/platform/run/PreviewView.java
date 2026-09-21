package com.autotest.platform.run;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record PreviewView(String method, String url, JsonNode query, JsonNode headers,
                          JsonNode cookies, JsonNode body, JsonNode options,
                          List<FilePreview> files) {
    public record FilePreview(String fileId, String originalName, long size, String sha256, String mimeType) {
    }
}
