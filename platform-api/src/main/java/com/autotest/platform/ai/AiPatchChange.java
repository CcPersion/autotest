package com.autotest.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;

public record AiPatchChange(String path, String changeType, JsonNode oldValue, JsonNode newValue,
                            boolean dangerous) {
}
