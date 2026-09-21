package com.autotest.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public interface ChatModelClient {
    List<AiStreamEvent> complete(AiModelConfigRecord config, List<AiChatMessage> messages,
                                 List<AiToolDefinition> tools);

    default JsonNode safeRequestPreview(List<AiChatMessage> messages, List<AiToolDefinition> tools) {
        return null;
    }
}
