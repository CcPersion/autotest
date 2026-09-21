package com.autotest.platform.run;

import com.autotest.platform.environment.EnvironmentRecord;
import com.autotest.platform.environment.EnvironmentRepository;
import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunServiceTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void rejectsUndefinedVariableBeforeInsertingPendingRun() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        RunRepository runs = mock(RunRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        when(projects.findById(projectId)).thenReturn(project(projectId));
        when(environments.findById(projectId, environmentId)).thenReturn(environment(projectId, environmentId));
        RunService service = new RunService(runs, projects, environments, json);

        ApiDomainException error = assertThrows(ApiDomainException.class, () -> service.create(
                projectId, environmentId, "API_CASE", UUID.randomUUID(), json.readTree("""
                {"planId":"undefined","baseUrl":"http://127.0.0.1","method":"GET",
                 "urlTemplate":"/orders/${missing}","body":{"type":"NONE"}}
                """), "run-undefined", UUID.randomUUID()));

        assertEquals("VALIDATION_FAILED", error.code());
        verify(runs, never()).insert(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void keepsSecretReferenceLegalDuringPlatformPreflight() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        RunRepository runs = mock(RunRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        when(projects.findById(projectId)).thenReturn(project(projectId));
        when(environments.findById(projectId, environmentId)).thenReturn(environment(projectId, environmentId));
        when(runs.findByIdempotencyKey(projectId, "run-secret")).thenReturn(null);
        when(runs.insert(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(mock(RunRecord.class));
        RunService service = new RunService(runs, projects, environments, json);

        assertDoesNotThrow(() -> service.create(projectId, environmentId, "API_CASE", UUID.randomUUID(),
                json.readTree("""
                {"planId":"secret","baseUrl":"http://127.0.0.1","method":"GET",
                 "urlTemplate":"/health","headers":[{"name":"Authorization","value":"Bearer ${secret:token}"}],
                 "body":{"type":"NONE"}}
                """), "run-secret", UUID.randomUUID()));
    }

    @Test
    void validatesSuiteMemberPlanAgainstItsOwnVariableScopes() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        RunRepository runs = mock(RunRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        when(projects.findById(projectId)).thenReturn(project(projectId));
        when(environments.findById(projectId, environmentId)).thenReturn(environment(projectId, environmentId));
        when(runs.findByIdempotencyKey(projectId, "run-suite-scope")).thenReturn(null);
        when(runs.insert(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(mock(RunRecord.class));
        RunService service = new RunService(runs, projects, environments, json);

        assertDoesNotThrow(() -> service.create(projectId, environmentId, "TEST_SUITE", UUID.randomUUID(),
                json.readTree("""
                {"targetType":"TEST_SUITE","suiteSteps":[
                  {"memberId":"member-1","position":0,"targetType":"API_CASE","targetId":"case-1",
                   "plan":{"baseUrl":"http://127.0.0.1","urlTemplate":"/orders/${id}",
                           "variableScopes":{"caseVariables":{"id":"case-id"}},"body":{"type":"NONE"}}}
                ]}
                """), "run-suite-scope", UUID.randomUUID()));
    }

    @Test
    void doesNotUseSuiteRootVariablesOrAnotherMemberWhenValidatingEnabledMembers() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        RunRepository runs = mock(RunRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        when(projects.findById(projectId)).thenReturn(project(projectId));
        when(environments.findById(projectId, environmentId)).thenReturn(environment(projectId, environmentId));
        RunService service = new RunService(runs, projects, environments, json);

        ApiDomainException error = assertThrows(ApiDomainException.class, () -> service.create(
                projectId, environmentId, "TEST_SUITE", UUID.randomUUID(), json.readTree("""
                {"targetType":"TEST_SUITE","variables":{"shared":"root"},"suiteSteps":[
                  {"memberId":"member-1","position":0,"targetType":"API_CASE","enabled":true,
                   "plan":{"baseUrl":"http://127.0.0.1","urlTemplate":"/orders/${shared}",
                           "variableScopes":{"caseVariables":{}},"body":{"type":"NONE"}}}
                ]}
                """), "run-suite-member-isolation", UUID.randomUUID()));

        assertEquals("VALIDATION_FAILED", error.code());
        verify(runs, never()).insert(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsDisabledSuiteMemberDuringVariablePreflight() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        RunRepository runs = mock(RunRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        when(projects.findById(projectId)).thenReturn(project(projectId));
        when(environments.findById(projectId, environmentId)).thenReturn(environment(projectId, environmentId));
        when(runs.findByIdempotencyKey(projectId, "run-suite-disabled")).thenReturn(null);
        when(runs.insert(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(mock(RunRecord.class));
        RunService service = new RunService(runs, projects, environments, json);

        assertDoesNotThrow(() -> service.create(projectId, environmentId, "TEST_SUITE", UUID.randomUUID(),
                json.readTree("""
                {"targetType":"TEST_SUITE","suiteSteps":[
                  {"memberId":"disabled","position":0,"targetType":"API_CASE","enabled":false,
                   "plan":{"urlTemplate":"/${missing}","body":{"type":"NONE"}}},
                  {"memberId":"enabled","position":1,"targetType":"API_CASE","enabled":true,
                   "plan":{"baseUrl":"http://127.0.0.1","urlTemplate":"/health",
                           "variableScopes":{"caseVariables":{}},"body":{"type":"NONE"}}}
                ]}
                """), "run-suite-disabled", UUID.randomUUID()));
    }

    @Test
    void rejectsWhenAnyEnabledDataRowMissesAReferencedVariable() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        RunRepository runs = mock(RunRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        when(projects.findById(projectId)).thenReturn(project(projectId));
        when(environments.findById(projectId, environmentId)).thenReturn(environment(projectId, environmentId));
        RunService service = new RunService(runs, projects, environments, json);

        ApiDomainException error = assertThrows(ApiDomainException.class, () -> service.create(
                projectId, environmentId, "API_CASE", UUID.randomUUID(), json.readTree("""
                {"planId":"rows","baseUrl":"http://127.0.0.1","method":"GET",
                 "urlTemplate":"/orders/${id}","dataRows":[
                   {"id":"row-1","enabled":true,"values":{"id":"one"}},
                   {"id":"row-2","enabled":true,"values":{"other":"two"}}
                 ],"body":{"type":"NONE"}}
                """), "run-missing-row-value", UUID.randomUUID()));

        assertEquals("VALIDATION_FAILED", error.code());
        verify(runs, never()).insert(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void validatesFormatDateUsingTheSameDateTimeFormatterContractAsRunner() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        RunRepository runs = mock(RunRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        when(projects.findById(projectId)).thenReturn(project(projectId));
        when(environments.findById(projectId, environmentId)).thenReturn(environment(projectId, environmentId));
        when(runs.findByIdempotencyKey(projectId, "run-formatdate-valid")).thenReturn(null);
        when(runs.insert(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(mock(RunRecord.class));
        RunService service = new RunService(runs, projects, environments, json);

        assertDoesNotThrow(() -> service.create(projectId, environmentId, "API_CASE", UUID.randomUUID(),
                json.readTree("""
                {"planId":"formatdate-valid","baseUrl":"http://127.0.0.1","method":"GET",
                 "urlTemplate":"/{{$formatdate:yyyy-MM-dd}}","body":{"type":"NONE"}}
                """), "run-formatdate-valid", UUID.randomUUID()));

        assertDoesNotThrow(() -> service.create(projectId, environmentId, "API_CASE", UUID.randomUUID(),
                json.readTree("""
                {"planId":"formatdate-uppercase-valid","baseUrl":"http://127.0.0.1","method":"GET",
                 "urlTemplate":"/{{$formatdate:YYYY-MM-dd}}","body":{"type":"NONE"}}
                """), "run-formatdate-uppercase-valid", UUID.randomUUID()));

        ApiDomainException error = assertThrows(ApiDomainException.class, () -> service.create(
                projectId, environmentId, "API_CASE", UUID.randomUUID(), json.readTree("""
                {"planId":"formatdate-invalid","baseUrl":"http://127.0.0.1","method":"GET",
                 "urlTemplate":"/{{$formatdate:[}}","body":{"type":"NONE"}}
                """), "run-formatdate-invalid", UUID.randomUUID()));
        assertEquals("VALIDATION_FAILED", error.code());

        assertThrows(ApiDomainException.class, () -> service.create(
                projectId, environmentId, "API_CASE", UUID.randomUUID(), json.readTree("""
                {"planId":"formatdate-extra-close","baseUrl":"http://127.0.0.1","method":"GET",
                 "urlTemplate":"/{{$formatdate:yyyy-MM-dd]}}","body":{"type":"NONE"}}
                """), "run-formatdate-extra-close", UUID.randomUUID()));

        assertThrows(ApiDomainException.class, () -> service.create(
                projectId, environmentId, "API_CASE", UUID.randomUUID(), json.readTree("""
                {"planId":"formatdate-blank","baseUrl":"http://127.0.0.1","method":"GET",
                 "urlTemplate":"/{{$formatdate: }}","body":{"type":"NONE"}}
                """), "run-formatdate-blank", UUID.randomUUID()));

        assertThrows(ApiDomainException.class, () -> service.create(
                projectId, environmentId, "API_CASE", UUID.randomUUID(), json.readTree("""
                {"planId":"formatdate-uppercase-function","baseUrl":"http://127.0.0.1","method":"GET",
                 "urlTemplate":"/{{$FORMATDATE:yyyy-MM-dd}}","body":{"type":"NONE"}}
                """), "run-formatdate-uppercase-function", UUID.randomUUID()));
    }

    private static ProjectRecord project(UUID id) {
        Instant now = Instant.now();
        return new ProjectRecord(id, "project", "", 0, false, now, now,
                List.of("127.0.0.1"));
    }

    private static EnvironmentRecord environment(UUID projectId, UUID id) {
        Instant now = Instant.now();
        return new EnvironmentRecord(id, projectId, "local", "http://127.0.0.1", 
                new ObjectMapper().createObjectNode(), new ObjectMapper().createObjectNode(),
                0, false, now, now);
    }
}
