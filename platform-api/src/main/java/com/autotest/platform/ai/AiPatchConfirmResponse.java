package com.autotest.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;

public record AiPatchConfirmResponse(String targetType, JsonNode asset, int revision) {
}
