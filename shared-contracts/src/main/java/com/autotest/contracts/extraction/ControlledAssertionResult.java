package com.autotest.contracts.extraction;

import com.fasterxml.jackson.databind.JsonNode;

/** 一条断言的稳定、可报告事实。 */
public record ControlledAssertionResult(int ruleIndex, String type, String operator, String expression,
                                        JsonNode expected, JsonNode actual, boolean passed,
                                        String errorCode, String message) {

    public ControlledAssertionResult {
        type = type == null ? "" : type;
        operator = operator == null ? "" : operator;
        expression = expression == null ? "" : expression;
        errorCode = errorCode == null ? "" : errorCode;
        message = message == null ? "" : message;
    }
}
