package com.autotest.platform.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class F401SecurityContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsFiveFieldCronAndComputesTheNextOccurrence() {
        CronSchedule schedule = CronSchedule.parse("*/5 * * * *");

        assertEquals(Instant.parse("2026-09-12T04:35:00Z"),
                schedule.next(Instant.parse("2026-09-12T04:31:00Z"), ZoneId.of("UTC")));
    }

    @Test
    void rejectsShellFragmentsAndImpossibleCron() {
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("0 * * * *; rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("61 * * * *"));
    }

    @Test
    void triggerTokenIsOneWayAndUsesConstantTimeMatching() {
        String token = TriggerTokenCodec.issue();
        String hash = TriggerTokenCodec.hash(token);

        assertTrue(token.startsWith("aat_"));
        assertNotEquals(token, hash);
        assertTrue(TriggerTokenCodec.matches(token, hash));
        assertFalse(TriggerTokenCodec.matches(token + "x", hash));
    }

    @Test
    void webhookPayloadContainsOnlySafeRunSummaryAndHasHmacSignature() throws Exception {
        var source = json.readTree("{\"runId\":\"r1\",\"status\":\"FAILED\","
                + "\"response\":{\"password\":\"raw-password\",\"body\":\"raw-body\"},"
                + "\"log\":\"raw-log\",\"secret\":\"raw-secret\"}");

        var safe = WebhookSecurity.sanitizePayload(source);
        String body = safe.toString();

        assertTrue(body.contains("r1"));
        assertTrue(body.contains("FAILED"));
        assertFalse(body.contains("raw-password"));
        assertFalse(body.contains("raw-body"));
        assertFalse(body.contains("raw-log"));
        assertFalse(body.contains("raw-secret"));

        String first = WebhookSecurity.sign("webhook-secret", body);
        String second = WebhookSecurity.sign("webhook-secret", body);
        assertEquals(first, second);
        assertTrue(first.startsWith("sha256="));
    }
}
