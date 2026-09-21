package com.autotest.platform.importer;

import com.autotest.platform.api.ApiCaseResponse;
import com.autotest.platform.api.ApiDefinitionResponse;

import java.util.List;

public record ImportConfirmResponse(List<ApiDefinitionResponse> definitions,
                                    List<ApiCaseResponse> cases) {
    public ImportConfirmResponse {
        definitions = List.copyOf(definitions == null ? List.of() : definitions);
        cases = List.copyOf(cases == null ? List.of() : cases);
    }
}
