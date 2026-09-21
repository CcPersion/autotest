package com.autotest.platform.report;

import com.autotest.platform.run.RunRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CleanupStatusResolverTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void marksMissingCleanupAsNotExecutedAfterInterruptedRun() throws Exception {
        UUID cleanup = UUID.randomUUID();
        assertEquals("NOT_EXECUTED", CleanupStatusResolver.resolve(run("INTERRUPTED", cleanup), List.of()));
    }

    @Test
    void distinguishesSuccessfulAndFailedCleanupResults() throws Exception {
        UUID cleanup = UUID.randomUUID();
        RunRecord run = run("FAILED", cleanup);
        assertEquals("PASSED", CleanupStatusResolver.resolve(run, List.of(result(cleanup, "PASSED"))));
        assertEquals("FAILED", CleanupStatusResolver.resolve(run, List.of(result(cleanup, "FAILED"))));
    }

    private RunRecord run(String status, UUID cleanup) throws Exception {
        return new RunRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SCENARIO", UUID.randomUUID(),
                UUID.randomUUID(), status,
                json.readTree("{\"scenarioSteps\":[{\"stepId\":\"" + cleanup + "\",\"section\":\"CLEANUP\"}]}"),
                "idempotency", "5.6.3", Instant.now(), Instant.now(), 1, null, null, null, false, Instant.now());
    }

    private StepResultRecord result(UUID stepId, String status) {
        return new StepResultRecord(UUID.randomUUID(), UUID.randomUUID(), stepId, stepId.toString(), 0, status, 1,
                object(), object(), array(), array(), object(), null, null, Instant.now());
    }

    private JsonNode object() {
        return json.createObjectNode();
    }

    private JsonNode array() {
        return json.createArrayNode();
    }
}
