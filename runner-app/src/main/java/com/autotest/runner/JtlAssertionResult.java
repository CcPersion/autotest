package com.autotest.runner;

/** JMeter XML JTL 中一个 assertionResult 的受控摘要。 */
record JtlAssertionResult(String name, boolean passed, String message) {

    JtlAssertionResult {
        name = name == null ? "" : name;
        message = message == null ? "" : message;
    }
}
