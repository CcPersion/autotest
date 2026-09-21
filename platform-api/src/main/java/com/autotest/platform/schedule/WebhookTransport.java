package com.autotest.platform.schedule;

import java.util.Map;

@FunctionalInterface
public interface WebhookTransport {
    int post(String url, Map<String, String> headers, String body) throws Exception;
}
