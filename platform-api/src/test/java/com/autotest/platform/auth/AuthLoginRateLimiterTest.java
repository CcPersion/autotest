package com.autotest.platform.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthLoginRateLimiterTest {

    @Test
    void locksOnFifthFailureWithinWindowAndExpiresAfterFiveMinutes() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("127.0.0.1", " Admin@Example.com ");
        }
        assertTrue(limiter.isLocked("127.0.0.1", "admin@example.com"));

        clock.advanceSeconds(299);
        assertTrue(limiter.isLocked("127.0.0.1", "ADMIN@example.com"));
        clock.advanceSeconds(1);
        assertFalse(limiter.isLocked("127.0.0.1", "admin@example.com"));
    }

    @Test
    void failuresOutsideWindowDoNotAccumulateAndSuccessClearsState() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("127.0.0.1", "admin@example.com");
        }
        clock.advanceSeconds(300);
        limiter.recordFailure("127.0.0.1", "admin@example.com");
        assertFalse(limiter.isLocked("127.0.0.1", "admin@example.com"));

        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("127.0.0.1", "admin@example.com");
        }
        assertTrue(limiter.isLocked("127.0.0.1", "admin@example.com"));
        limiter.clear("127.0.0.1", "admin@example.com");
        assertFalse(limiter.isLocked("127.0.0.1", "admin@example.com"));
    }

    @Test
    void countsTheMostRecentFiveMinutesAcrossAWindowBoundary() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        limiter.recordFailure("127.0.0.1", "admin@example.com");
        clock.advanceSeconds(299);
        limiter.recordFailure("127.0.0.1", "admin@example.com");
        clock.advanceSeconds(1);
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("127.0.0.1", "admin@example.com");
        }

        assertTrue(limiter.isLocked("127.0.0.1", "admin@example.com"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
