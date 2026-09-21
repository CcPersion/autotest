package com.autotest.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;

public record AiStreamEvent(String type, String content, String toolName, JsonNode arguments) {
    public static AiStreamEvent text(String content) {
        return new AiStreamEvent("TEXT", content, null, null);
    }

    public static AiStreamEvent toolCall(String name, JsonNode arguments) {
        return new AiStreamEvent("TOOL_CALL", null, name, arguments);
    }

    public static AiStreamEvent toolResult(String name, JsonNode result) {
        return new AiStreamEvent("TOOL_RESULT", result == null ? "{}" : result.toString(), name, result);
    }

    public static AiStreamEvent done() {
        return new AiStreamEvent("DONE", null, null, null);
    }

    public static AiStreamEvent error(String content) {
        return new AiStreamEvent("ERROR", content, null, null);
    }
}
