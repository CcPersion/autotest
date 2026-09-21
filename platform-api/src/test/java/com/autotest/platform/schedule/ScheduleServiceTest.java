package com.autotest.platform.schedule;

import com.autotest.platform.environment.EnvironmentRecord;
import com.autotest.platform.environment.EnvironmentRepository;
import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.run.RunRecord;
import com.autotest.platform.run.RunService;
import com.autotest.platform.suite.TestSuiteRecord;
import com.autotest.platform.suite.TestSuiteRunService;
import com.autotest.platform.suite.TestSuiteService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleServiceTest {
    @Test
    void disabledScheduleHasNoNextRunAndPersistsNormalizedDependencies() {
        UUID projectId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();
        ProjectRepository projects = mock(ProjectRepository.class);
        ScheduleRepository schedules = mock(ScheduleRepository.class);
        TestSuiteService suites = mock(TestSuiteService.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        TestSuiteRunService suiteRuns = mock(TestSuiteRunService.class);
        when(projects.findByIdForUpdate(projectId)).thenReturn(new ProjectRecord(projectId, "demo", "", 0, false, now, now));
        when(schedules.existsName(projectId, "nightly", null)).thenReturn(false);
        when(suites.get(projectId, suiteId)).thenReturn(new TestSuiteRecord(suiteId, projectId, "回归", "", environmentId,
                0, false, now, now, List.of()));
        when(environments.findById(projectId, environmentId)).thenReturn(new EnvironmentRecord(environmentId, projectId,
                "test", "http://127.0.0.1", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(), 0, false, now, now));
        ScheduleRecord saved = new ScheduleRecord(UUID.randomUUID(), projectId, suiteId, environmentId, "nightly",
                "0 0 * * *", "UTC", false, 0, null, null, actorId, actorId, now, now);
        when(schedules.insert(eq(projectId), eq("nightly"), eq(suiteId), eq(environmentId), eq("0 0 * * *"), eq("UTC"),
                eq(false), eq(null), eq(actorId))).thenReturn(saved);

        ScheduleRecord result = new ScheduleService(schedules, projects, suites, environments, suiteRuns)
                .create(projectId, new ScheduleWrite(" nightly ", suiteId, environmentId, "0 0 * * *", "UTC", false, null), actorId);

        assertNull(result.nextRunAt());
        verify(schedules).insert(eq(projectId), eq("nightly"), eq(suiteId), eq(environmentId), eq("0 0 * * *"), eq("UTC"),
                eq(false), eq(null), eq(actorId));
    }

    @Test
    void dueScheduleCreatesAnIdempotentRunAndAdvancesNextOccurrence() {
        UUID scheduleId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant due = Instant.parse("2026-09-12T04:30:00Z");
        ScheduleRepository schedules = mock(ScheduleRepository.class);
        TestSuiteRunService suiteRuns = mock(TestSuiteRunService.class);
        ScheduleRecord schedule = new ScheduleRecord(scheduleId, projectId, suiteId, environmentId, "every-five",
                "*/5 * * * *", "UTC", true, 0, due, null, actorId, actorId, due.minusSeconds(100), due.minusSeconds(100));
        when(schedules.findDue(any())).thenReturn(List.of(schedule));
        when(suiteRuns.createResult(eq(projectId), eq(suiteId), eq(environmentId),
                eq("schedule:" + scheduleId + ":" + due.toEpochMilli()), eq(actorId)))
                .thenReturn(new RunService.CreateResult(mock(RunRecord.class), true));
        when(schedules.advance(eq(projectId), eq(scheduleId), eq(due), eq(Instant.parse("2026-09-12T04:35:00Z")), any()))
                .thenReturn(1);

        int created = new ScheduleService(schedules, mock(ProjectRepository.class), mock(TestSuiteService.class),
                mock(EnvironmentRepository.class), suiteRuns).runDue(Instant.parse("2026-09-12T04:31:00Z"));

        org.junit.jupiter.api.Assertions.assertEquals(1, created);
        verify(suiteRuns).createResult(eq(projectId), eq(suiteId), eq(environmentId),
                eq("schedule:" + scheduleId + ":" + due.toEpochMilli()), eq(actorId));
    }
}
