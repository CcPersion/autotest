package com.autotest.platform.runner;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunnerStatusServiceTest {

    private final RunnerStatusRepository repository = mock(RunnerStatusRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);
    private final RunnerStatusService service = new RunnerStatusService(repository, clock, 30);

    @Test
    void marksFreshHeartbeatOnlineAndStaleHeartbeatOffline() {
        UUID runnerId = UUID.randomUUID();
        service.heartbeat(runnerId, "runner-0.1.0", "5.6.3", null, 2);
        verify(repository).upsert(any(), any(), any());

        when(repository.findAll()).thenReturn(List.of(
                new RunnerStatusRecord(runnerId, "runner-0.1.0", "5.6.3", null, 2,
                        clock.instant().minusSeconds(29), clock.instant()),
                new RunnerStatusRecord(UUID.randomUUID(), "runner-0.1.0", "5.6.3", null, 0,
                        clock.instant().minusSeconds(31), clock.instant())));

        List<RunnerStatusService.Status> statuses = service.list();
        assertEquals("ONLINE", statuses.get(0).status());
        assertEquals("OFFLINE", statuses.get(1).status());
    }
}
