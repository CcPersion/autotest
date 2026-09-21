package com.autotest.platform.runner;

import com.autotest.platform.security.ApiDomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RunnerStatusService {
    private final RunnerStatusRepository repository;
    private final Clock clock;
    private final long onlineTimeoutSeconds;

    @Autowired
    public RunnerStatusService(RunnerStatusRepository repository) {
        this(repository, Clock.systemUTC(), 30);
    }

    RunnerStatusService(RunnerStatusRepository repository, Clock clock, long onlineTimeoutSeconds) {
        this.repository = repository;
        this.clock = clock;
        this.onlineTimeoutSeconds = onlineTimeoutSeconds;
    }

    public RunnerStatusRecord heartbeat(UUID runnerId, String runnerVersion, String jmeterVersion,
                                        UUID activeRunId, int queueDepth) {
        if (runnerId == null || blank(runnerVersion) || blank(jmeterVersion) || queueDepth < 0) {
            throw invalid("Runner 心跳参数不合法");
        }
        Instant now = Instant.now(clock);
        return repository.upsert(new RunnerStatusWrite(runnerVersion.strip(), jmeterVersion.strip(), activeRunId, queueDepth),
                runnerId, now);
    }

    public List<Status> list() {
        Instant cutoff = Instant.now(clock).minusSeconds(onlineTimeoutSeconds);
        return repository.findAll().stream().map(record -> new Status(record.runnerId(), record.runnerVersion(),
                record.jmeterVersion(), record.activeRunId(), record.queueDepth(), record.lastSeenAt(),
                record.lastSeenAt().isAfter(cutoff) ? "ONLINE" : "OFFLINE")).toList();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank() || value.length() > 64;
    }

    private static ApiDomainException invalid(String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", message);
    }

    public record Status(UUID runnerId, String runnerVersion, String jmeterVersion, UUID activeRunId,
                         int queueDepth, Instant lastSeenAt, String status) {
    }
}
