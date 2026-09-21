package com.autotest.runner;

import java.util.Objects;

/** 通过 JMeter CookieManager 注入的初始 Cookie。 */
public record JmeterCookie(String name, String value, String domain, String path, boolean secure, boolean enabled) {

    public JmeterCookie {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Cookie name 不能为空");
        }
        value = Objects.requireNonNull(value, "Cookie value 不能为空");
        domain = domain == null ? "" : domain;
        path = path == null || path.isBlank() ? "/" : path;
    }

    public JmeterCookie(String name, String value) {
        this(name, value, "", "/", false, true);
    }

    public JmeterCookie(String name, String value, String domain, String path, boolean secure) {
        this(name, value, domain, path, secure, true);
    }
}
