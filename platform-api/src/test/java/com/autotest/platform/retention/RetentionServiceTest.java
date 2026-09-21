package com.autotest.platform.retention;

import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.security.ApiDomainException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionServiceTest {

    private final UUID projectId = UUID.randomUUID();
    private final RetentionRepository retention = mock(RetentionRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);
    private final RetentionService service = new RetentionService(retention, projects, clock);

    @Test
    void defaultsToThirtyDaysAndRejectsOutOfRange() {
        when(projects.findById(projectId)).thenReturn(project());
        when(retention.find(projectId)).thenReturn(null);

        assertEquals(30, service.get(projectId).retentionDays());
        assertEquals(400, assertThrows(ApiDomainException.class,
                () -> service.update(projectId, new RetentionWrite(0, 0))).status());
        assertEquals(400, assertThrows(ApiDomainException.class,
                () -> service.update(projectId, new RetentionWrite(3651, 0))).status());
    }

    @Test
    void cleanupDeletesOnlyTerminalRunsBeforeCutoff() {
        when(projects.findById(projectId)).thenReturn(project());
        when(retention.find(projectId)).thenReturn(new RetentionRecord(projectId, 1, 2, clock.instant()));
        when(retention.deleteOldStepResults(projectId, Instant.parse("2026-09-11T00:00:00Z"))).thenReturn(4);
        when(retention.deleteOldRuns(projectId, Instant.parse("2026-09-11T00:00:00Z"))).thenReturn(2);

        assertEquals(2, service.cleanup(projectId));
        verify(retention).deleteOldStepResults(projectId, Instant.parse("2026-09-11T00:00:00Z"));
        verify(retention).deleteOldRuns(projectId, Instant.parse("2026-09-11T00:00:00Z"));
    }

    private ProjectRecord project() {
        return new ProjectRecord(projectId, "project", null, 0, false, clock.instant(), clock.instant());
    }
}
