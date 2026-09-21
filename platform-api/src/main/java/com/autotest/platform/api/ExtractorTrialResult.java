package com.autotest.platform.api;

import com.autotest.contracts.extraction.ControlledExtractionResult;
import com.fasterxml.jackson.databind.JsonNode;

/** 即时试算对外返回的稳定提取事实。 */
public record ExtractorTrialResult(int ruleIndex, String type, String expression, String variable,
                                   boolean matched, boolean usedDefault, JsonNode value,
                                   String valueType, boolean failed, String errorCode, String message) {

    static ExtractorTrialResult from(ControlledExtractionResult result) {
        return new ExtractorTrialResult(result.ruleIndex(), result.type(), result.expression(), result.variable(),
                result.matched(), result.usedDefault(), result.value(), result.valueType(), result.failed(),
                result.errorCode(), result.message());
    }
}
