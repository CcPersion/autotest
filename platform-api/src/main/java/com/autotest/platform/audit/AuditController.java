package com.autotest.platform.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/audit-events")
public class AuditController {
    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public List<AuditEventRecord> list(@PathVariable UUID projectId,
                                      @RequestParam(required = false) String action,
                                      @RequestParam(required = false) String traceId,
                                      @RequestParam(defaultValue = "50") int limit) {
        return service.list(projectId, action, traceId, limit);
    }
}
