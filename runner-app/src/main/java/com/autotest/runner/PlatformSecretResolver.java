package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 通过内部 Runner 接口读取单个密钥；响应正文只在内存中短暂存在。 */
final class PlatformSecretResolver implements SecretResolver {

    private final HttpClient http;
    private final ObjectMapper json;
    private final String platformApiUrl;
    private final String callbackToken;

    PlatformSecretResolver(String platformApiUrl, String callbackToken) {
        if (platformApiUrl == null || platformApiUrl.isBlank() || callbackToken == null || callbackToken.isBlank()) {
            throw new IllegalArgumentException("Platform 密钥回调配置不能为空");
        }
        this.http = HttpClient.newHttpClient();
        this.json = new ObjectMapper();
        this.platformApiUrl = platformApiUrl.replaceAll("/+$", "");
        this.callbackToken = callbackToken;
    }

    @Override
    public String resolve(UUID projectId, String name) throws Exception {
        if (projectId == null || name == null || name.isBlank()) {
            throw new IllegalArgumentException("密钥引用不能为空");
        }
        String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest request = HttpRequest.newBuilder(URI.create(platformApiUrl + "/api/v1/internal/projects/"
                        + projectId + "/secrets/" + encodedName + "/value"))
                .header("Accept", "application/json")
                .header("X-Runner-Token", callbackToken)
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalArgumentException("Runner 密钥解析失败");
        }
        JsonNode body = json.readTree(response.body());
        JsonNode value = body == null ? null : body.get("value");
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw new IllegalArgumentException("Runner 密钥响应无效");
        }
        return value.textValue();
    }
}
