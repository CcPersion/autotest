package com.autotest.platform.suite;

import com.autotest.platform.environment.EnvironmentRecord;
import com.autotest.platform.environment.EnvironmentRepository;
import com.autotest.platform.run.RunRecord;
import com.autotest.platform.run.RunService;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSuiteRunServiceTest {
    @Test
    void rejectsArchivedEnvironmentBeforeCreatingRun() {
        UUID projectId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        Instant now = Instant.now();
        TestSuiteRecord suite = new TestSuiteRecord(suiteId, projectId, "回归", "", environmentId, 0, false,
                now, now, List.of());
        TestSuiteService suites = mock(TestSuiteService.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        when(suites.get(projectId, suiteId)).thenReturn(suite);
        when(environments.findById(projectId, environmentId)).thenReturn(new EnvironmentRecord(environmentId, projectId,
                "测试", "http://test.local", new ObjectMapper().createObjectNode(), new ObjectMapper().createObjectNode(),
                0, true, now, now));

        TestSuiteRunService service = new TestSuiteRunService(suites, environments,
                mock(TestSuiteRunPlanBuilder.class), mock(RunService.class));

        assertThrows(ApiDomainException.class, () -> service.create(projectId, suiteId, environmentId,
                "suite-run", UUID.randomUUID()));
    }
}
