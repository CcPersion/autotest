package com.autotest.contracts.extraction;

import com.fasterxml.jackson.databind.JsonNode;

/** 一条提取规则的事实结果。matched 与 usedDefault 严格分离。 */
public record ControlledExtractionResult(int ruleIndex, String type, String expression, String variable,
                                         boolean matched, boolean usedDefault, JsonNode value,
                                         String valueType, boolean failed, String errorCode, String message) {

    public ControlledExtractionResult {
        type = type == null ? "" : type;
        expression = expression == null ? "" : expression;
        variable = variable == null ? "" : variable;
        valueType = valueType == null ? "missing" : valueType;
        errorCode = errorCode == null ? "" : errorCode;
        message = message == null ? "" : message;
    }
}
