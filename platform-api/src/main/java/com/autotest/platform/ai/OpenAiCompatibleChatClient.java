package com.autotest.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class OpenAiCompatibleChatClient implements ChatModelClient {
    private final ObjectMapper mapper;
    private final AiApiKeyResolver credentials;
    private final HttpClient http;

    public OpenAiCompatibleChatClient(ObjectMapper mapper, AiApiKeyResolver credentials) {
        this.mapper = mapper;
        this.credentials = credentials;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public List<AiStreamEvent> complete(AiModelConfigRecord config, List<AiChatMessage> messages,
                                        List<AiToolDefinition> tools) {
        try {
            ObjectNode request = mapper.createObjectNode().put("model", config.modelName()).put("stream", false);
            ArrayNode messageArray = request.putArray("messages");
            for (AiChatMessage message : messages) {
                messageArray.addObject().put("role", message.role())
                        .put("content", AiPromptSanitizer.sanitize(message.content()));
            }
            ArrayNode toolArray = request.putArray("tools");
            for (AiToolDefinition tool : tools) {
                ObjectNode function = toolArray.addObject().put("type", "function").putObject("function")
                        .put("name", tool.name()).put("description", tool.description());
                function.set("parameters", tool.parameters());
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(config.baseUrl()))
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString()));
            String key = credentials.resolve(config.apiKeySecretRef());
            if (key != null && !key.isBlank()) {
                builder.header("Authorization", "Bearer " + key);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("模型服务返回非成功状态");
            }
            return parse(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型服务请求被中断");
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("模型服务请求失败");
        }
    }

    private List<AiStreamEvent> parse(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode message = root.path("choices").path(0).path("message");
        List<AiStreamEvent> events = new ArrayList<>();
        if (message.hasNonNull("content")) {
            events.add(AiStreamEvent.text(AiPromptSanitizer.sanitize(message.path("content").asText())));
        }
        for (JsonNode call : message.path("tool_calls")) {
            String name = call.path("function").path("name").asText("");
            JsonNode arguments = call.path("function").path("arguments");
            if (!name.isBlank()) {
                JsonNode parsed = arguments.isTextual() ? mapper.readTree(arguments.asText()) : arguments;
                events.add(AiStreamEvent.toolCall(name, parsed));
            }
        }
        if (events.isEmpty()) {
            events.add(AiStreamEvent.text("模型未返回可处理内容。"));
        }
        events.add(AiStreamEvent.done());
        return events;
    }

    private static URI endpoint(String baseUrl) {
        String value = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(value.endsWith("/chat/completions") ? value : value + "/chat/completions");
    }
}
