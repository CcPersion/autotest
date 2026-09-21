package com.autotest.platform.retention;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/retention")
public class RetentionController {

    private final RetentionService service;

    public RetentionController(RetentionService service) {
        this.service = service;
    }

    @GetMapping
    public RetentionResponse get(@PathVariable UUID projectId) {
        return RetentionResponse.from(service.get(projectId));
    }

    @PutMapping
    public RetentionResponse update(@PathVariable UUID projectId, @RequestBody RetentionWrite request) {
        return RetentionResponse.from(service.update(projectId, request));
    }

    @PostMapping("/cleanup")
    public CleanupResponse cleanup(@PathVariable UUID projectId) {
        return new CleanupResponse(service.cleanup(projectId), Instant.now());
    }

    public record RetentionResponse(UUID projectId, int retentionDays, int revision, Instant updatedAt) {
        static RetentionResponse from(RetentionRecord value) {
            return new RetentionResponse(value.projectId(), value.retentionDays(), value.revision(), value.updatedAt());
        }
    }

    public record CleanupResponse(int deletedRuns, Instant cleanedAt) {
    }
}
