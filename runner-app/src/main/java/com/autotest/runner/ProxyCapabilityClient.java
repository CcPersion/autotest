package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Obtains a short-lived, run-bound capability for the controlled HTTP proxy. */
public final class ProxyCapabilityClient {
    private final URI endpoint;
    private final String callbackToken;
    private final ApprovedDnsResolver dns;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();

    public ProxyCapabilityClient(String platformApiUrl, String callbackToken) {
        this(platformApiUrl, callbackToken, new ApprovedDnsResolver(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    ProxyCapabilityClient(String platformApiUrl, String callbackToken, ApprovedDnsResolver dns,
                          HttpClient client) {
        if (platformApiUrl == null || platformApiUrl.isBlank()) throw new IllegalArgumentException("平台 API 地址不能为空");
        if (callbackToken == null || callbackToken.isBlank()) throw new IllegalArgumentException("Runner 回调令牌不能为空");
        this.endpoint = URI.create(platformApiUrl.replaceAll("/+$", "") + "/api/v1/internal/runs/");
        this.callbackToken = callbackToken;
        this.dns = Objects.requireNonNull(dns, "dns");
        this.client = Objects.requireNonNull(client, "client");
    }

    public JmeterPlan authorize(UUID runId, JmeterPlan plan) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(plan, "plan");
        if (plan.proxy() == null) return plan;
        URI target = targetUri(plan);
        URI proxy = proxyUri(plan.proxy());
        ApprovedDnsResolver.Resolution targetResolution = dns.resolve(target, plan.targetAllowlist());
        // The platform independently resolves and authorizes both endpoints;
        // these observations are sent only as an audit hint, never as proof.
        ApprovedDnsResolver.Resolution proxyResolution = dns.resolve(proxy, plan.targetAllowlist());
        ObjectNode request = json.createObjectNode();
        request.put("targetUrl", target.toString());
        request.put("proxyUrl", proxy.toString());
        var approvedAddresses = request.putArray("approvedAddresses");
        targetResolution.addresses().forEach(address -> approvedAddresses.add(address.getHostAddress()));
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint.resolve(runId + "/proxy-authorizations"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Runner-Token", callbackToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("代理授权失败");
            JsonNode body = json.readTree(response.body());
            String capability = body.path("capability").asText("");
            if (capability.isBlank()) throw new IllegalStateException("代理授权响应缺少能力令牌");
            // Ensure the Platform response is structurally complete before the
            // token is put into JMeter's proxy password field.
            if (!body.path("addresses").isArray() || !body.path("proxyAddresses").isArray()
                    || body.path("proxyAddresses").size() != proxyResolution.addresses().size()) {
                throw new IllegalStateException("代理授权响应地址集合不完整");
            }
            return plan.withProxy(plan.proxy().withCapability(capability));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("代理授权被中断");
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException illegal) throw illegal;
            throw new IllegalStateException("代理授权请求失败", exception);
        }
    }

    private static URI targetUri(JmeterPlan plan) {
        URI base = URI.create(plan.baseUrl());
        URI target = base.resolve(plan.urlTemplate());
        if (target.getHost() == null) throw new IllegalArgumentException("执行计划目标主机为空");
        return target;
    }

    private static URI proxyUri(JmeterProxy proxy) {
        return URI.create(proxy.scheme() + "://" + proxy.host() + ":" + proxy.port() + "/");
    }
}
