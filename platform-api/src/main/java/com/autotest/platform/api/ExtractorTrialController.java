package com.autotest.platform.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** HTTP 提取器即时试算接口。projectId 仅用于 URL 资源边界，不触发项目读写。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/extractor-trials")
public class ExtractorTrialController {

    private final ExtractorTrialService service;

    public ExtractorTrialController(ExtractorTrialService service) {
        this.service = service;
    }

    @PostMapping
    public ExtractorTrialResponse trial(@PathVariable UUID projectId,
                                        @RequestBody ExtractorTrialRequest request) {
        if (projectId == null) throw new IllegalArgumentException("projectId 不合法");
        return new ExtractorTrialResponse(service.evaluate(request));
    }
}
