package com.autotest.platform.suite;

import com.autotest.platform.api.ApiCaseRecord;
import com.autotest.platform.api.ApiCaseRepository;
import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.scenario.ScenarioRecord;
import com.autotest.platform.scenario.ScenarioRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSuiteServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void createsSuiteWithStableMemberOrder() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        Instant now = Instant.now();
        ProjectRepository projects = mock(ProjectRepository.class);
        TestSuiteRepository suites = mock(TestSuiteRepository.class);
        ApiCaseRepository cases = mock(ApiCaseRepository.class);
        ScenarioRepository scenarios = mock(ScenarioRepository.class);
        when(projects.findByIdForUpdate(projectId)).thenReturn(new ProjectRecord(projectId, "demo", "", 0,
                false, now, now));
        when(suites.existsActiveName(projectId, "回归集合")).thenReturn(false);
        when(cases.findActiveByProjectId(projectId, caseId)).thenReturn(new ApiCaseRecord(caseId, projectId,
                UUID.randomUUID(), "登录", json.readTree("{}"), json.readTree("{}"), json.readTree("[]"),
                0, false, now, now));
        when(scenarios.findById(projectId, scenarioId)).thenReturn(new ScenarioRecord(scenarioId, projectId,
                "业务场景", "", json.readTree("{}"), json.readTree("{}"), 0, false, now, now, List.of()));
        TestSuiteRecord saved = new TestSuiteRecord(UUID.randomUUID(), projectId, "回归集合", "", null,
                0, false, now, now, List.of());
        when(suites.insert(any(), any(), any(), any(), any(), any())).thenReturn(saved);

        TestSuiteService service = new TestSuiteService(suites, projects, cases, scenarios);
        TestSuiteWrite request = new TestSuiteWrite("回归集合", "", null, List.of(
                new TestSuiteWrite.MemberWrite(UUID.randomUUID(), "SCENARIO", scenarioId, 1, true),
                new TestSuiteWrite.MemberWrite(UUID.randomUUID(), "API_CASE", caseId, 0, true)), null);

        TestSuiteRecord result = service.create(projectId, request, actorId);

        assertEquals(saved.id(), result.id());
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(suites).insert(any(), any(), any(), any(), captor.capture(), any());
        List<?> members = captor.getValue();
        assertEquals("API_CASE", ((TestSuiteWrite.MemberWrite) members.get(0)).targetType());
        assertEquals("SCENARIO", ((TestSuiteWrite.MemberWrite) members.get(1)).targetType());
    }

    @Test
    void rejectsUnsupportedMemberType() throws Exception {
        UUID projectId = UUID.randomUUID();
        ProjectRepository projects = mock(ProjectRepository.class);
        when(projects.findByIdForUpdate(projectId)).thenReturn(new ProjectRecord(projectId, "demo", "", 0,
                false, Instant.now(), Instant.now()));
        TestSuiteService service = new TestSuiteService(mock(TestSuiteRepository.class), projects,
                mock(ApiCaseRepository.class), mock(ScenarioRepository.class));
        TestSuiteWrite request = new TestSuiteWrite("回归集合", "", null, List.of(
                new TestSuiteWrite.MemberWrite(UUID.randomUUID(), "HTTP", UUID.randomUUID(), 0, true)), null);

        assertThrows(RuntimeException.class, () -> service.create(projectId, request, UUID.randomUUID()));
    }

    @Test
    void rejectsMissingReferencedCase() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        ProjectRepository projects = mock(ProjectRepository.class);
        when(projects.findByIdForUpdate(projectId)).thenReturn(new ProjectRecord(projectId, "demo", "", 0,
                false, Instant.now(), Instant.now()));
        ApiCaseRepository cases = mock(ApiCaseRepository.class);
        when(cases.findActiveByProjectId(projectId, caseId)).thenReturn(null);
        TestSuiteService service = new TestSuiteService(mock(TestSuiteRepository.class), projects, cases,
                mock(ScenarioRepository.class));
        TestSuiteWrite request = new TestSuiteWrite("回归集合", "", null, List.of(
                new TestSuiteWrite.MemberWrite(UUID.randomUUID(), "API_CASE", caseId, 0, true)), null);

        assertThrows(RuntimeException.class, () -> service.create(projectId, request, UUID.randomUUID()));
    }
}
