package com.autotest.platform.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Deque;

/** 单实例登录失败限制器；进程重启后状态自然清空。 */
@Component
public class LoginRateLimiter {

    private static final long WINDOW_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final long LOCK_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final int MAX_FAILURES = 5;

    private final Clock clock;
    private final Map<String, Attempt> attempts = new HashMap<>();

    public LoginRateLimiter() {
        this(Clock.systemUTC());
    }

    public LoginRateLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized boolean isLocked(String remoteAddress, String username) {
        long now = clock.millis();
        purge(now);
        Attempt attempt = attempts.get(key(remoteAddress, username));
        return attempt != null && attempt.lockedUntil > now;
    }

    public synchronized void recordFailure(String remoteAddress, String username) {
        long now = clock.millis();
        purge(now);
        String key = key(remoteAddress, username);
        Attempt attempt = attempts.computeIfAbsent(key, ignored -> new Attempt());
        removeExpiredFailures(attempt, now);
        attempt.failures.addLast(now);
        if (attempt.failures.size() >= MAX_FAILURES) {
            attempt.lockedUntil = now + LOCK_MILLIS;
        }
    }

    public synchronized void clear(String remoteAddress, String username) {
        attempts.remove(key(remoteAddress, username));
    }

    private void purge(long now) {
        Iterator<Map.Entry<String, Attempt>> iterator = attempts.entrySet().iterator();
        while (iterator.hasNext()) {
            Attempt attempt = iterator.next().getValue();
            removeExpiredFailures(attempt, now);
            if (attempt.failures.isEmpty() && attempt.lockedUntil <= now) {
                iterator.remove();
            }
        }
    }

    private static void removeExpiredFailures(Attempt attempt, long now) {
        long cutoff = now - WINDOW_MILLIS;
        while (!attempt.failures.isEmpty() && attempt.failures.peekFirst() <= cutoff) {
            attempt.failures.removeFirst();
        }
    }

    private static String key(String remoteAddress, String username) {
        return String.valueOf(remoteAddress) + "\u0000" + UsernameNormalizer.normalize(username);
    }

    private static final class Attempt {
        private final Deque<Long> failures = new ArrayDeque<>();
        private long lockedUntil;
    }
}
