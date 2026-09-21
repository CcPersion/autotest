package com.autotest.runner;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JmeterHeaderCookiePresenceAssertionTest {

    @Test
    void matchesHeaderAndCookieNamesWithoutExposingValues() {
        SampleResult sample = new SampleResult();
        sample.setResponseHeaders("Content-Type: application/json\r\nSet-Cookie: session=abc123; Path=/\r\n");

        JmeterHeaderCookiePresenceAssertion header = new JmeterHeaderCookiePresenceAssertion();
        header.setKind("HEADER");
        header.setTargetName("Content-Type");
        assertFalse(header.getResult(sample).isError());
        assertFalse(header.getResult(sample).isFailure());

        JmeterHeaderCookiePresenceAssertion cookie = new JmeterHeaderCookiePresenceAssertion();
        cookie.setKind("COOKIE");
        cookie.setTargetName("session");
        assertFalse(cookie.getResult(sample).isError());
        assertFalse(cookie.getResult(sample).isFailure());
    }

    @Test
    void reportsMissingTargetAsFailure() {
        SampleResult sample = new SampleResult();
        sample.setResponseHeaders("Content-Type: application/json\r\n");

        JmeterHeaderCookiePresenceAssertion assertion = new JmeterHeaderCookiePresenceAssertion();
        assertion.setKind("COOKIE");
        assertion.setTargetName("session");

        assertTrue(assertion.getResult(sample).isFailure());
    }
}
