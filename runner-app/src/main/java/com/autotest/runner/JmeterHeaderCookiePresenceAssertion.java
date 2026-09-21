package com.autotest.runner;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;

import java.util.regex.Pattern;

/** 受控 Header/Cookie 存在性断言，避免把响应头解析交给用户脚本。 */
public final class JmeterHeaderCookiePresenceAssertion extends AbstractTestElement implements Assertion {

    private static final String KIND_PROPERTY = "autotest.presence.kind";
    private static final String NAME_PROPERTY = "autotest.presence.name";

    public void setKind(String kind) {
        if (!"HEADER".equals(kind) && !"COOKIE".equals(kind)) {
            throw new IllegalArgumentException("存在性断言类型必须是 HEADER 或 COOKIE");
        }
        setProperty(KIND_PROPERTY, kind);
    }

    public void setTargetName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Header/Cookie 名称不能为空");
        }
        setProperty(NAME_PROPERTY, name);
    }

    @Override
    public AssertionResult getResult(SampleResult sampleResult) {
        String kind = getPropertyAsString(KIND_PROPERTY, "");
        String name = getPropertyAsString(NAME_PROPERTY, "");
        AssertionResult result = new AssertionResult(getName());
        String headers = sampleResult.getResponseHeaders() == null ? "" : sampleResult.getResponseHeaders();
        String regex = "COOKIE".equals(kind)
                ? "(?im)(?:^|\\r?\\n)Set-Cookie\\s*:\\s*" + Pattern.quote(name) + "="
                : "(?im)(?:^|\\r?\\n)" + Pattern.quote(name) + "\\s*:";
        if (!Pattern.compile(regex).matcher(headers).find()) {
            result.setFailure(true);
            result.setFailureMessage(kind + " 未找到 " + name);
        }
        return result;
    }
}
