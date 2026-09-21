package com.autotest.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Runner 完成任务后仅回传状态摘要；不上传 JMX/JTL/日志路径和执行计划。 */
public final class RunCompletionNotifier {
    private final URI endpoint;
    private final String callbackToken;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();

    public RunCompletionNotifier(String platformApiUrl, String callbackToken) {
        this(platformApiUrl, callbackToken, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    RunCompletionNotifier(String platformApiUrl, String callbackToken, HttpClient client) {
        if (platformApiUrl == null || platformApiUrl.isBlank()) throw new IllegalArgumentException("平台 API 地址不能为空");
        if (callbackToken == null || callbackToken.isBlank()) throw new IllegalArgumentException("Runner 回调令牌不能为空");
        this.endpoint = URI.create(platformApiUrl.endsWith("/") ? platformApiUrl.substring(0, platformApiUrl.length() - 1)
                : platformApiUrl);
        this.callbackToken = callbackToken;
        this.client = client;
    }

    public void notify(RunRecord run) throws Exception {
        if (run == null || run.id() == null) throw new IllegalArgumentException("运行结果不能为空");
        ObjectNode body = json.createObjectNode();
        body.put("status", run.status());
        if (run.exitCode() != null) body.put("exitCode", run.exitCode());
        if (run.startedAt() != null) body.put("startedAt", run.startedAt().toString());
        if (run.finishedAt() != null) body.put("finishedAt", run.finishedAt().toString());
        URI target = URI.create(endpoint + "/api/v1/internal/runs/" + run.id() + "/finished");
        HttpRequest request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Runner-Token", callbackToken)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("运行完成回调失败");
        }
    }
}
