package com.autotest.contracts.extraction;

import java.util.LinkedHashMap;
import java.util.Map;

/** 试算或 Runner 报告使用的脱敏 HTTP 响应样本。 */
public record HttpResponseSample(int status, long durationMs, String bodyText,
                                 Map<String, String> headers, Map<String, String> cookies) {

    public HttpResponseSample {
        if (status < 0 || status > 999) throw new IllegalArgumentException("HTTP 状态码不合法");
        if (durationMs < 0) throw new IllegalArgumentException("响应耗时不能为负数");
        bodyText = bodyText == null ? "" : bodyText;
        headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
        cookies = cookies == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(cookies));
    }
}
