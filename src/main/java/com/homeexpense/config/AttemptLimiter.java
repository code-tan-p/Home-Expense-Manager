package com.homeexpense.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Counts tries per key (an address or an email) inside a time window; used for sign-in, sign-up and recovery. */
public class AttemptLimiter {

    private final Clock clock;
    private final int max;
    private final Duration window;
    private final Map<String, Deque<Instant>> tries = new ConcurrentHashMap<>();

    public AttemptLimiter(Clock clock, int max, Duration window) {
        this.clock = clock;
        this.max = max;
        this.window = window;
    }

    public void record(String key) {
        if (tries.size() > 20_000) {
            tries.values().removeIf(Deque::isEmpty);
        }
        Deque<Instant> recent = tries.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (recent) {
            recent.addLast(clock.instant());
            trim(recent);
        }
    }

    public boolean blocked(String key) {
        Deque<Instant> recent = tries.get(key);
        if (recent == null) {
            return false;
        }
        synchronized (recent) {
            trim(recent);
            return recent.size() >= max;
        }
    }

    public void clear(String key) {
        tries.remove(key);
    }

    private void trim(Deque<Instant> recent) {
        Instant cutoff = clock.instant().minus(window);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
            recent.removeFirst();
        }
    }
}
