package com.autotest.runner;

import java.util.List;

/** JMeter JTL 单样本的脱敏平台表示。 */
public record JtlSample(String stepId, long elapsedMs, int responseCode, String responseMessage,
                        boolean success, String failureMessage, String url, String extractionsJson,
                        String responseBody, String method, String requestBody,
                        String responseHeaders, List<JtlAssertionResult> assertionResults) {

    public JtlSample {
        responseHeaders = responseHeaders == null ? "" : responseHeaders;
        assertionResults = assertionResults == null ? List.of() : List.copyOf(assertionResults);
    }

    public JtlSample(String stepId, long elapsedMs, int responseCode, String responseMessage,
                     boolean success, String failureMessage, String url, String extractionsJson) {
        this(stepId, elapsedMs, responseCode, responseMessage, success, failureMessage, url,
                extractionsJson, "", "", "", "", List.of());
    }

    public JtlSample(String stepId, long elapsedMs, int responseCode, String responseMessage,
                     boolean success, String failureMessage, String url, String extractionsJson,
                     String responseBody) {
        this(stepId, elapsedMs, responseCode, responseMessage, success, failureMessage, url,
                extractionsJson, responseBody, "", "", "", List.of());
    }

    public JtlSample(String stepId, long elapsedMs, int responseCode, String responseMessage,
                     boolean success, String failureMessage, String url,
                     String method, String requestBody, String responseBody) {
        this(stepId, elapsedMs, responseCode, responseMessage, success, failureMessage, url,
                "[]", responseBody, method, requestBody, "", List.of());
    }

    public JtlSample(String stepId, long elapsedMs, int responseCode, String responseMessage,
                     boolean success, String failureMessage, String url) {
        this(stepId, elapsedMs, responseCode, responseMessage, success, failureMessage, url, "[]");
    }

    JtlSample forDataRow(String rowId) {
        if (rowId == null || rowId.isBlank()) return this;
        return new JtlSample(stepId + "#" + rowId, elapsedMs, responseCode, responseMessage,
                success, failureMessage, url, extractionsJson, responseBody, method, requestBody,
                responseHeaders, assertionResults);
    }

    JtlSample forAttempt(int attempt) {
        if (attempt <= 1) return this;
        return new JtlSample(stepId + "#attempt-" + attempt, elapsedMs, responseCode, responseMessage,
                success, failureMessage, url, extractionsJson, responseBody, method, requestBody,
                responseHeaders, assertionResults);
    }
}
