package com.autotest.runner;

import com.autotest.contracts.network.TargetAllowlist;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 从单个接口定义和用例适配出的 Runner 编译输入。 */
public record JmeterPlan(
        String planId,
        String stepId,
        String baseUrl,
        String method,
        String urlTemplate,
        List<JmeterParameter> query,
        List<JmeterParameter> headers,
        JmeterBody body,
        Map<String, String> variables,
        List<JmeterAssertion> assertions,
        List<JmeterCookie> cookies,
        boolean followRedirects,
        int connectTimeoutMillis,
        int responseTimeoutMillis,
        JmeterProxy proxy,
        List<JmeterExtractor> extractors,
        JmeterClientCertificate clientCertificate,
        List<String> targetAllowlist,
        boolean targetPolicyRequired,
        boolean targetDnsRequired
) {

    public JmeterPlan(String planId, String stepId, String baseUrl, String method, String urlTemplate,
                      List<JmeterParameter> query, List<JmeterParameter> headers, JmeterBody body,
                      Map<String, String> variables, List<JmeterAssertion> assertions) {
        this(planId, stepId, baseUrl, method, urlTemplate, query, headers, body, variables, assertions,
                List.of(), true, 0, 0, null, List.of(), null, inferredAllowlist(baseUrl), true, false);
    }

    public JmeterPlan(String planId, String stepId, String baseUrl, String method, String urlTemplate,
                      List<JmeterParameter> query, List<JmeterParameter> headers, JmeterBody body,
                      Map<String, String> variables, List<JmeterAssertion> assertions,
                      List<JmeterCookie> cookies, boolean followRedirects, int connectTimeoutMillis,
                      int responseTimeoutMillis, JmeterProxy proxy) {
        this(planId, stepId, baseUrl, method, urlTemplate, query, headers, body, variables, assertions,
                cookies, followRedirects, connectTimeoutMillis, responseTimeoutMillis, proxy, List.of(), null,
                inferredAllowlist(baseUrl), true, false);
    }

    public JmeterPlan(String planId, String stepId, String baseUrl, String method, String urlTemplate,
                      List<JmeterParameter> query, List<JmeterParameter> headers, JmeterBody body,
                      Map<String, String> variables, List<JmeterAssertion> assertions,
                      List<JmeterCookie> cookies, boolean followRedirects, int connectTimeoutMillis,
                      int responseTimeoutMillis, JmeterProxy proxy, List<JmeterExtractor> extractors) {
        this(planId, stepId, baseUrl, method, urlTemplate, query, headers, body, variables, assertions,
                cookies, followRedirects, connectTimeoutMillis, responseTimeoutMillis, proxy, extractors, null,
                inferredAllowlist(baseUrl), true, false);
    }

    public JmeterPlan(String planId, String stepId, String baseUrl, String method, String urlTemplate,
                      List<JmeterParameter> query, List<JmeterParameter> headers, JmeterBody body,
                      Map<String, String> variables, List<JmeterAssertion> assertions,
                      List<JmeterCookie> cookies, boolean followRedirects, int connectTimeoutMillis,
                      int responseTimeoutMillis, JmeterProxy proxy, List<JmeterExtractor> extractors,
                      JmeterClientCertificate clientCertificate) {
        this(planId, stepId, baseUrl, method, urlTemplate, query, headers, body, variables, assertions,
                cookies, followRedirects, connectTimeoutMillis, responseTimeoutMillis, proxy, extractors,
                clientCertificate, inferredAllowlist(baseUrl), true, false);
    }

    public JmeterPlan {
        planId = required(planId, "planId");
        stepId = required(stepId, "stepId");
        baseUrl = required(baseUrl, "baseUrl");
        method = required(method, "method");
        if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method)) {
            throw new IllegalArgumentException("不支持的 HTTP 方法: " + method);
        }
        urlTemplate = required(urlTemplate, "urlTemplate");
        query = query == null ? List.of() : List.copyOf(query);
        headers = headers == null ? List.of() : List.copyOf(headers);
        body = Objects.requireNonNull(body, "body 不能为空");
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
        cookies = cookies == null ? List.of() : List.copyOf(cookies);
        extractors = extractors == null ? List.of() : List.copyOf(extractors);
        targetAllowlist = targetAllowlist == null ? List.of() : TargetAllowlist.parse(targetAllowlist).rules();
        if (targetPolicyRequired && targetAllowlist.isEmpty()) {
            throw new IllegalArgumentException("执行计划缺少项目目标白名单");
        }
        if (connectTimeoutMillis < 0 || responseTimeoutMillis < 0) {
            throw new IllegalArgumentException("超时不能为负数");
        }
    }

    /** 兼容 Runner 单元测试中的旧构造器；生产运行计划由平台附带明确白名单。 */
    private static List<String> inferredAllowlist(String baseUrl) {
        URI uri = URI.create(baseUrl);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("baseUrl 缺少合法主机");
        }
        int port = uri.getPort();
        return port >= 0 ? List.of(host + ":" + port) : List.of(host);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }

    public JmeterPlan withProxy(JmeterProxy value) {
        return new JmeterPlan(planId, stepId, baseUrl, method, urlTemplate, query, headers, body,
                variables, assertions, cookies, followRedirects, connectTimeoutMillis, responseTimeoutMillis,
                value, extractors, clientCertificate, targetAllowlist, targetPolicyRequired, targetDnsRequired);
    }
}
