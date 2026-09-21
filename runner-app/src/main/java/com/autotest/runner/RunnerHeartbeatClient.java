package com.autotest.runner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Runner 到 Platform 的最小心跳；请求正文不包含数据库密码或回调令牌。 */
public final class RunnerHeartbeatClient {
    private final HttpClient http;
    private final URI endpoint;
    private final String callbackToken;
    private final UUID runnerId;

    public RunnerHeartbeatClient(String platformApiUrl, String callbackToken, UUID runnerId) {
        if (platformApiUrl == null || platformApiUrl.isBlank() || callbackToken == null || callbackToken.isBlank()
                || runnerId == null) throw new IllegalArgumentException("Runner 心跳配置不能为空");
        this.http = HttpClient.newHttpClient();
        this.endpoint = URI.create(platformApiUrl.replaceAll("/+$", "")
                + "/api/v1/internal/runners/" + runnerId + "/heartbeat");
        this.callbackToken = callbackToken;
        this.runnerId = runnerId;
    }

    RunnerHeartbeatClient(HttpClient http, URI endpoint, String callbackToken, UUID runnerId) {
        this.http = http;
        this.endpoint = endpoint;
        this.callbackToken = callbackToken;
        this.runnerId = runnerId;
    }

    public void send(String runnerVersion, String jmeterVersion, UUID activeRunId, int queueDepth) {
        if (runnerVersion == null || runnerVersion.isBlank() || jmeterVersion == null || jmeterVersion.isBlank()
                || queueDepth < 0) throw new IllegalArgumentException("Runner 心跳参数不合法");
        String body = "{\"runnerVersion\":\"" + escape(runnerVersion) + "\",\"jmeterVersion\":\""
                + escape(jmeterVersion) + "\",\"activeRunId\":" + (activeRunId == null ? "null" : "\"" + activeRunId + "\"")
                + ",\"queueDepth\":" + queueDepth + "}";
        HttpRequest request = HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json")
                .header("X-Runner-Token", callbackToken)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Runner 心跳被平台拒绝");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Runner 心跳被中断", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Runner 心跳发送失败", exception);
        }
    }

    UUID runnerId() {
        return runnerId;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
