package com.autotest.contracts.extraction;

/** 共享受控表达式求值器的稳定公开名称。 */
public final class ControlledExpressionEvaluator {

    private ControlledExpressionEvaluator() {
    }

    public static ControlledExtractionResult extract(int ruleIndex, ExtractionRule rule,
                                                     HttpResponseSample response) {
        return ControlledExtractionEvaluator.extract(ruleIndex, rule, response);
    }
}
