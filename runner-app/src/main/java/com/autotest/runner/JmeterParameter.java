package com.autotest.runner;

/** Query 或 Header 的平台参数。 */
public record JmeterParameter(String name, String value, boolean enabled) {

    public JmeterParameter {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("参数名不能为空");
        }
        if (value == null) {
            throw new IllegalArgumentException("参数值不能为空");
        }
    }
}
