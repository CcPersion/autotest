package com.autotest.runner;

/** HTTP 代理配置；密码只能来自已解析的受控变量。 */
public record JmeterProxy(String scheme, String host, int port, String username, String password,
                          String capability) {

    public JmeterProxy(String scheme, String host, int port, String username, String password) {
        this(scheme, host, port, username, password, "");
    }

    public JmeterProxy {
        scheme = scheme == null || scheme.isBlank() ? "http" : scheme;
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("代理 host 不能为空");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("代理 port 必须在 1-65535 范围内");
        }
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        capability = capability == null ? "" : capability;
    }

    public boolean authenticated() {
        return !username.isBlank();
    }

    public JmeterProxy withCapability(String value) {
        return new JmeterProxy(scheme, host, port, username, password, value);
    }
}
