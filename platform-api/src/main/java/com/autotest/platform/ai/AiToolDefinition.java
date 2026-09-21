package com.autotest.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;

public record AiToolDefinition(String name, String description, JsonNode parameters) {
}
