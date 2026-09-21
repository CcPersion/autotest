package com.autotest.platform.retention;

import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class RetentionService {

    private static final int DEFAULT_DAYS = 30;
    private final RetentionRepository retention;
    private final ProjectRepository projects;
    private final Clock clock;

    @Autowired
    public RetentionService(RetentionRepository retention, ProjectRepository projects) {
        this(retention, projects, Clock.systemUTC());
    }

    RetentionService(RetentionRepository retention, ProjectRepository projects, Clock clock) {
        this.retention = retention;
        this.projects = projects;
        this.clock = clock;
    }

    public RetentionRecord get(UUID projectId) {
        requireProject(projectId);
        RetentionRecord value = retention.find(projectId);
        return value == null ? new RetentionRecord(projectId, DEFAULT_DAYS, 0, Instant.now(clock)) : value;
    }

    @Transactional
    public RetentionRecord update(UUID projectId, RetentionWrite write) {
        requireProject(projectId);
        if (write == null || write.retentionDays() == null || write.retentionDays() < 1 || write.retentionDays() > 3650
                || write.revision() == null || write.revision() < 0) {
            throw validation("保留天数必须在 1 到 3650 天，且需要 revision");
        }
        RetentionRecord current = retention.find(projectId);
        if (current == null && write.revision() != 0) throw conflict();
        if (current != null && current.revision() != write.revision()) throw conflict();
        if (retention.update(projectId, write.retentionDays(), write.revision()) != 1) throw conflict();
        return get(projectId);
    }

    @Transactional
    public int cleanup(UUID projectId) {
        RetentionRecord settings = get(projectId);
        Instant cutoff = Instant.now(clock).minus(settings.retentionDays(), ChronoUnit.DAYS);
        retention.deleteOldStepResults(projectId, cutoff);
        return retention.deleteOldRuns(projectId, cutoff);
    }

    private void requireProject(UUID projectId) {
        if (projects.findById(projectId) == null) throw new ApiDomainException(HttpStatus.NOT_FOUND.value(),
                "RESOURCE_NOT_FOUND", "项目不存在");
    }

    private static ApiDomainException validation(String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", message);
    }

    private static ApiDomainException conflict() {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT", "保留策略版本已变化");
    }
}
