package com.autotest.platform.api;

import java.util.List;

/** 试算结果统一包装。 */
public record ExtractorTrialResponse(List<ExtractorTrialResult> results) {

    public ExtractorTrialResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
