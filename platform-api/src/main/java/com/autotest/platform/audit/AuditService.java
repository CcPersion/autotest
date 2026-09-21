package com.autotest.platform.audit;

import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

@Service
public class AuditService {
    private final AuditRepository repository;
    private final Clock clock;

    @Autowired
    public AuditService(AuditRepository repository, ObjectMapper ignoredJson) {
        this(repository, ignoredJson, Clock.systemUTC());
    }

    AuditService(AuditRepository repository, ObjectMapper ignoredJson, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public AuditEventRecord record(UUID actorId, UUID projectId, String action, String resourceType,
                                   UUID resourceId, String traceId, UUID runId, UUID stepId, JsonNode metadata) {
        String normalizedAction = required(action, "审计动作不能为空", 64);
        String normalizedType = required(resourceType, "审计资源类型不能为空", 64);
        String normalizedTrace = traceId == null || traceId.isBlank() ? null : traceId.strip();
        if (normalizedTrace != null && normalizedTrace.length() > 128) {
            throw invalid("审计 traceId 过长");
        }
        return repository.insert(new AuditEventWrite(actorId, projectId, normalizedAction, normalizedType,
                resourceId, normalizedTrace, runId, stepId, AuditSanitizer.sanitize(metadata)));
    }

    public java.util.List<AuditEventRecord> list(UUID projectId, String action, String traceId, int limit) {
        if (projectId == null) throw invalid("项目不能为空");
        return repository.find(projectId, action, traceId, limit);
    }

    private static String required(String value, String message, int max) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > max) throw invalid(message);
        return normalized;
    }

    private static ApiDomainException invalid(String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", message);
    }
}
